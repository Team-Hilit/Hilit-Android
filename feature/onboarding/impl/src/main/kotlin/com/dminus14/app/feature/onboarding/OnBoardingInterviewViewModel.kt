package com.dminus14.app.feature.onboarding

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.core.common.pdf.PdfInvalidReason
import com.dminus14.app.core.common.pdf.PdfValidationResult
import com.dminus14.app.core.common.pdf.validatePdf
import com.dminus14.app.domain.exception.AccountSuspendedException
import com.dminus14.app.domain.exception.FreeTextNotRelevantException
import com.dminus14.app.domain.exception.InvalidJdUrlException
import com.dminus14.app.domain.exception.JdValidationLimitExceededException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.InterviewSessionRequest
import com.dminus14.app.domain.model.InterviewSessionStatusType
import com.dminus14.app.domain.model.PortfolioStatus
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.DeletePortfolioUseCase
import com.dminus14.app.domain.usecase.GetInterviewSessionUseCase
import com.dminus14.app.domain.usecase.GetPortfolioOverviewUseCase
import com.dminus14.app.domain.usecase.GetPortfolioStatusUseCase
import com.dminus14.app.domain.usecase.MakeInterviewSessionUseCase
import com.dminus14.app.domain.usecase.SaveInterviewSessionProgressUseCase
import com.dminus14.app.domain.usecase.UploadPortfolioUseCase
import com.dminus14.app.domain.usecase.ValidateJdUrlUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
@Suppress("TooManyFunctions", "LargeClass")
class OnBoardingInterviewViewModel
    @Inject
    @Suppress("LongParameterList")
    constructor(
        @ApplicationContext private val context: Context,
        private val checkUserProfile: CheckUserProfileUseCase,
        private val getPortfolioOverview: GetPortfolioOverviewUseCase,
        private val uploadPortfolio: UploadPortfolioUseCase,
        private val getPortfolioStatus: GetPortfolioStatusUseCase,
        private val deletePortfolio: DeletePortfolioUseCase,
        private val validateJdUrl: ValidateJdUrlUseCase,
        private val makeInterviewSession: MakeInterviewSessionUseCase,
        private val getInterviewSession: GetInterviewSessionUseCase,
        private val saveInterviewSessionProgress: SaveInterviewSessionProgressUseCase,
        @OnBoardingIoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : MviViewModel<
            OnBoardingInterviewIntent,
            OnBoardingInterviewState,
            OnBoardingInterviewEffect,
        >(
            OnBoardingInterviewState(),
        ) {
        private var jobRole: String? = null
        private var careerYears: Int? = null
        private var readyPortfolioId: String? = null
        private var existingPortfolioId: String? = null
        private var existingPortfolioFileName: String? = null
        private var existingReplaceAvailable: Boolean = false
        private var existingDeleteAvailable: Boolean = false
        private var jdUrl: String? = null
        private var jdText: String? = null
        private var jdValidationJob: Job? = null

        @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod")
        override fun onIntent(intent: OnBoardingInterviewIntent) {
            when (intent) {
                OnBoardingInterviewIntent.Load -> {
                    load()
                }

                OnBoardingInterviewIntent.ClickClose -> {
                    sendEffect(OnBoardingInterviewEffect.CloseRequested)
                }

                OnBoardingInterviewIntent.ClickSkip -> {
                    advanceStep()
                }

                OnBoardingInterviewIntent.ClickContinue -> {
                    onContinueClick()
                }

                OnBoardingInterviewIntent.ClickPrevious -> {
                    onPreviousClick()
                }

                is OnBoardingInterviewIntent.JobDescriptionTabChange -> {
                    val nextTab =
                        JobDescriptionTab.entries.getOrNull(intent.index)
                            ?: state.value.jobDescriptionTab
                    val cancelValidation =
                        nextTab != state.value.jobDescriptionTab &&
                            state.value.jdLinkStatus == JdLinkStatus.Validating
                    if (cancelValidation) {
                        jdValidationJob?.cancel()
                    }
                    reduce {
                        copy(
                            jobDescriptionTab = nextTab,
                            jdLinkStatus =
                                if (cancelValidation) JdLinkStatus.Idle else jdLinkStatus,
                            jdLinkSubText =
                                if (cancelValidation) "" else jdLinkSubText,
                        )
                    }
                }

                is OnBoardingInterviewIntent.JobDescriptionLinkChange -> {
                    onJobDescriptionLinkChange(intent.value)
                }

                is OnBoardingInterviewIntent.JobDescriptionTextChange -> {
                    // 붙여넣기로 3000자를 넘겨 들어와도 잘라서 반영한다.
                    reduce {
                        copy(
                            jobDescriptionText = intent.value.take(JD_TEXT_MAX_LENGTH),
                            jdTextError = null,
                        )
                    }
                }

                OnBoardingInterviewIntent.ClickPortfolioUpload -> {
                    sendEffect(OnBoardingInterviewEffect.LaunchPortfolioPicker)
                }

                OnBoardingInterviewIntent.ClickPortfolioRemove -> {
                    removePortfolio()
                }

                OnBoardingInterviewIntent.PortfolioStepEntered -> {
                    maybeShowExistingPortfolioNotice()
                }

                OnBoardingInterviewIntent.ClickExistingPortfolioConfirm -> {
                    reduce { copy(existingPortfolioModalPhase = ExistingPortfolioModalPhase.None) }
                }

                OnBoardingInterviewIntent.ClickAutoDismissNotice,
                OnBoardingInterviewIntent.AutoDismissNoticeTimedOut,
                -> {
                    dismissAutoNoticeAndAdvance()
                }

                is OnBoardingInterviewIntent.PortfolioFileSelected -> {
                    startPortfolioUpload(intent)
                }

                is OnBoardingInterviewIntent.MainProjectTextChange -> {
                    // 붙여넣기 등으로 300자를 넘겨 들어와도 잘라서 반영한다.
                    reduce {
                        copy(
                            mainProjectText = intent.value.take(FREETEXT_MAX_LENGTH),
                            mainProjectError = null,
                        )
                    }
                }

                OnBoardingInterviewIntent.ClickRelevanceRetryPortfolio -> {
                    // "포트폴리오 다시 올리기": 다이얼로그를 닫고 포트폴리오 스텝으로 되돌아간다.
                    // 카운트는 초기화해 다음 시도가 처음부터 새로 세도록 한다.
                    reduce {
                        copy(
                            showRelevanceFailDialog = false,
                            step = OnBoardingInterviewStep.Portfolio,
                            mainProjectRelevanceFailCount = 0,
                            mainProjectError = null,
                            errorMessage = null,
                        )
                    }
                }

                OnBoardingInterviewIntent.ClickRelevanceProceedWithoutMainProject -> {
                    // "집중 프로젝트 없이 진행": free text를 비우고 preload를 다시 시작한다.
                    reduce {
                        copy(
                            step = OnBoardingInterviewStep.Preload,
                            showRelevanceFailDialog = false,
                            mainProjectText = "",
                            mainProjectError = null,
                            mainProjectRelevanceFailCount = 0,
                            errorMessage = null,
                        )
                    }
                    startPreload()
                }

                OnBoardingInterviewIntent.ClickPreloadFailureAcknowledged -> {
                    // Preload 실패 안내 다이얼로그 확인: MainProject로 되돌려 재시도 유도.
                    reduce {
                        copy(
                            step = OnBoardingInterviewStep.MainProject,
                            errorMessage = null,
                            loadingBasicInfo = OnBoardingLoadingStepStatus.Waiting,
                            loadingJd = OnBoardingLoadingStepStatus.Waiting,
                            loadingPortfolio = OnBoardingLoadingStepStatus.Waiting,
                        )
                    }
                }
            }
        }

        private fun load() {
            viewModelScope.launch {
                checkUserProfile()
                    .onSuccess { profile ->
                        jobRole = profile.jobRole
                        careerYears = profile.careerYears
                    }.onFailure { error ->
                        // 직무 연차 없을 경우 직무 연차 입력 온보딩으로 route할지 질문중
                        reduce { copy(errorMessage = error.message) }
                    }

                getPortfolioOverview()
                    .onSuccess { overview ->
                        val portfolio = overview.portfolio
                        existingPortfolioId = portfolio?.portfolioId
                        existingPortfolioFileName = portfolio?.fileName
                        existingReplaceAvailable = overview.isReplaceAvailable
                        existingDeleteAvailable = overview.isDeleteAvailable
                        // 재진입 시 기존 READY 포트폴리오는 즉시 노출·재사용한다.
                        // (스펙 S2: "정상 저장(READY) 포폴은 이탈해도 보존, 다음 진입 시 재사용")
                        if (portfolio != null && portfolio.status == PortfolioStatus.READY) {
                            readyPortfolioId = portfolio.portfolioId
                            reduce {
                                copy(
                                    portfolioFileName = portfolio.fileName,
                                    portfolioUploadProgress = PORTFOLIO_PROGRESS_COMPLETE,
                                )
                            }
                        }
                        maybeShowExistingPortfolioNotice()
                    }.onFailure { error ->
                        // 조회 실패를 무음 처리하면 사용자에게는 "포폴 없음"으로 보여, 재업로드 시
                        // 서버가 PORTFOLIO_ALREADY_EXISTS로 튕겨내는 혼란을 만든다.
                        // checkUserProfile 실패와 동일하게 errorMessage로 노출한다.
                        reduce { copy(errorMessage = error.message) }
                    }
            }
        }

        private fun onContinueClick() {
            when (state.value.step) {
                OnBoardingInterviewStep.JobDescription -> {
                    submitJobDescription()
                }

                OnBoardingInterviewStep.Portfolio -> {
                    if (readyPortfolioId == null) {
                        reduce { copy(portfolioErrorMessage = MESSAGE_PORTFOLIO_REQUIRED) }
                    } else {
                        advanceStep()
                    }
                }

                OnBoardingInterviewStep.MainProject -> {
                    submitMainProject()
                }

                OnBoardingInterviewStep.Preload -> {
                    Unit
                }
            }
        }

        /** JD 링크 입력을 반영하고, 이전 검증 결과를 지운다. API 검증은 계속하기에서 시작한다. */
        private fun onJobDescriptionLinkChange(value: String) {
            jdValidationJob?.cancel()
            jdUrl = null

            when {
                value.isEmpty() -> {
                    reduce {
                        copy(
                            jobDescriptionLink = value,
                            jdLinkStatus = JdLinkStatus.Idle,
                            jdLinkSubText = "",
                        )
                    }
                }

                !value.startsWith(HTTPS_SCHEME) -> {
                    reduce {
                        copy(
                            jobDescriptionLink = value,
                            jdLinkStatus = JdLinkStatus.Invalid,
                            jdLinkSubText = MESSAGE_LINK_FORMAT,
                        )
                    }
                }

                HTTPS_SCHEME.startsWith(value) -> {
                    // 아직 "https://" 스킴을 입력 중이라 포맷 오류로 보지 않는다.
                    reduce {
                        copy(
                            jobDescriptionLink = value,
                            jdLinkStatus = JdLinkStatus.Idle,
                            jdLinkSubText = "",
                        )
                    }
                }

                else -> {
                    // 새 입력에는 이전 검증 결과를 적용하지 않는다.
                    reduce {
                        copy(
                            jobDescriptionLink = value,
                            jdLinkStatus = JdLinkStatus.Idle,
                            jdLinkSubText = "",
                        )
                    }
                }
            }
        }

        private fun validateAndSubmitJdLink(link: String) {
            reduce { copy(jdLinkStatus = JdLinkStatus.Validating, jdLinkSubText = "") }
            jdValidationJob =
                viewModelScope.launch {
                    validateJdUrl(link)
                        .onSuccess { result ->
                            if (!isCurrentJdLinkValidation(link)) return@onSuccess
                            if (result.valid) {
                                jdUrl = link
                                jdText = null
                                reduce {
                                    copy(
                                        jdLinkStatus = JdLinkStatus.Valid,
                                        jdLinkSubText = "",
                                    )
                                }
                                advanceStep()
                            } else {
                                reduce {
                                    copy(
                                        jdLinkStatus = JdLinkStatus.Invalid,
                                        jdLinkSubText = result.message ?: MESSAGE_LINK_INVALID,
                                    )
                                }
                            }
                        }.onFailure { error ->
                            if (!isCurrentJdLinkValidation(link)) return@onFailure
                            handleJdLinkValidationFailure(error)
                        }
                }
        }

        private suspend fun handleJdLinkValidationFailure(error: Throwable) {
            when (error) {
                is JdValidationLimitExceededException,
                is InvalidJdUrlException,
                -> {
                    // 429는 고정 문구로 강제 노출한다 (스펙 S1 확정 카피).
                    val subText =
                        if (error is JdValidationLimitExceededException) {
                            MESSAGE_LINK_RATE_LIMIT
                        } else {
                            error.message
                        }
                    reduce {
                        copy(
                            jdLinkStatus = JdLinkStatus.Invalid,
                            jdLinkSubText = subText,
                        )
                    }
                }

                else -> {
                    reduce { copy(jdLinkStatus = JdLinkStatus.Idle) }
                    val event =
                        when (error) {
                            is NetworkUnavailableException -> GlobalAppEvent.ShowNetworkErrorAndExit
                            is ServerException -> GlobalAppEvent.ShowServerErrorAndExit
                            else -> GlobalAppEvent.ShowUnknownError
                        }
                    GlobalErrorHandler.emit(event)
                }
            }
        }

        private fun isCurrentJdLinkValidation(link: String): Boolean =
            state.value.let { current ->
                current.step == OnBoardingInterviewStep.JobDescription &&
                    current.jobDescriptionTab == JobDescriptionTab.Link &&
                    current.jobDescriptionLink == link &&
                    current.jdLinkStatus == JdLinkStatus.Validating
            }

        private fun submitJobDescription() {
            val current = state.value
            if (current.jobDescriptionTab == JobDescriptionTab.Text) {
                // 카운터(raw length)와 동일한 기준으로 검증·전송한다.
                val content = current.jobDescriptionText
                if (content.length < JD_TEXT_MIN_LENGTH) {
                    reduce { copy(jdTextError = MESSAGE_TEXT_TOO_SHORT) }
                    return
                }
                jdText = content
                jdUrl = null
                advanceStep()
                return
            }

            // 링크 탭: 빈 입력은 건너뛰고, 입력한 링크는 계속하기를 누를 때 검증한다.
            when {
                current.jdLinkStatus == JdLinkStatus.Validating -> {
                    Unit
                }

                current.jdLinkStatus == JdLinkStatus.Valid -> {
                    advanceStep()
                }

                current.jobDescriptionLink.isEmpty() -> {
                    jdUrl = null
                    jdText = null
                    advanceStep()
                }

                !current.jobDescriptionLink.startsWith(HTTPS_SCHEME) -> {
                    reduce {
                        copy(
                            jdLinkStatus = JdLinkStatus.Invalid,
                            jdLinkSubText = MESSAGE_LINK_FORMAT,
                        )
                    }
                }

                else -> {
                    validateAndSubmitJdLink(current.jobDescriptionLink)
                }
            }
        }

        /**
         * 집중 프로젝트는 선택 입력이라 비어 있으면 그냥 넘어가고, 입력했다면 최소 10자를
         * 만족해야 다음 스텝으로 진행한다. (최대 300자는 입력 필드에서 캡핑된다.)
         */
        private fun submitMainProject() {
            val content = state.value.mainProjectText
            if (content.isNotEmpty() && content.length < FREETEXT_MIN_LENGTH) {
                reduce { copy(mainProjectError = MESSAGE_FREETEXT_TOO_SHORT) }
                return
            }
            advanceStep()
        }

        /**
         * 업로드했거나 선택한 포트폴리오를 서버에서 삭제한다. 삭제는 파괴적·rate-limited라
         * 서버 성공을 확인한 뒤에만 로컬 상태를 정리하고, 실패하면 인라인 에러로 노출한다.
         */
        private fun removePortfolio() {
            val portfolioId = readyPortfolioId ?: return

            reduce { copy(isPortfolioProcessing = true, portfolioErrorMessage = null) }
            viewModelScope.launch {
                deletePortfolio(portfolioId)
                    .onSuccess {
                        readyPortfolioId = null
                        existingPortfolioId = null
                        existingPortfolioFileName = null
                        reduce {
                            copy(
                                portfolioFileName = null,
                                isPortfolioProcessing = false,
                                portfolioUploadProgress = 0,
                                hasShownExistingPortfolioNotice = false,
                                existingPortfolioModalPhase = ExistingPortfolioModalPhase.None,
                            )
                        }
                    }.onFailure { error ->
                        reduce {
                            copy(
                                isPortfolioProcessing = false,
                                portfolioErrorMessage = error.message,
                            )
                        }
                    }
            }
        }

        private fun startPortfolioUpload(intent: OnBoardingInterviewIntent.PortfolioFileSelected) {
            reduce {
                copy(
                    portfolioFileName = intent.fileName,
                    isPortfolioProcessing = true,
                    portfolioErrorMessage = null,
                    portfolioUploadProgress = 0,
                )
            }
            viewModelScope.launch {
                try {
                    val validation =
                        withContext(ioDispatcher) {
                            validatePdf(context.contentResolver, Uri.fromFile(intent.file))
                        }
                    val validationMessage = validation.toErrorMessageOrNull()
                    if (validationMessage != null) {
                        reduce {
                            copy(
                                isPortfolioProcessing = false,
                                portfolioFileName = null,
                                portfolioErrorMessage = validationMessage,
                                portfolioUploadProgress = 0,
                            )
                        }
                        return@launch
                    }

                    // 서버 스펙: pageCount 는 Positive int 필수. null 로 보내면 400.
                    // validatePdf 가 Valid 를 반환한 시점에 이미 pageCount 를 알고 있으므로 그대로 전달한다.
                    val pageCount = (validation as? PdfValidationResult.Valid)?.pageCount
                    uploadPortfolio(
                        file = intent.file,
                        fileName = intent.fileName,
                        pageCount = pageCount,
                    ).onSuccess { result -> pollPortfolioStatus(result.portfolioId) }
                        .onFailure { error ->
                            // 서버 매핑된 예외 메시지(스펙 6장 문구)를 그대로 보여준다.
                            // 매핑되지 않은 실패는 시스템 실패 안내로 폴백한다.
                            reduce {
                                copy(
                                    isPortfolioProcessing = false,
                                    portfolioFileName = null,
                                    portfolioErrorMessage =
                                        error.message ?: MESSAGE_PORTFOLIO_SYSTEM_FAILED,
                                    portfolioUploadProgress = 0,
                                )
                            }
                        }
                } finally {
                    // 검증 실패·업로드 성공·실패·폴링·취소 어떤 경로든 캐시 사본은 정리한다.
                    // 폴링은 서버 sessionId만 사용하므로 이 시점 이후 로컬 file 참조는 없다.
                    intent.file.delete()
                }
            }
        }

        @Suppress("detekt:LongMethod", "detekt:ReturnCount")
        private suspend fun pollPortfolioStatus(portfolioId: String) {
            repeat(MAX_POLL_ATTEMPTS) { attempt ->
                val status =
                    getPortfolioStatus(portfolioId).getOrElse { error ->
                        reduce {
                            copy(
                                isPortfolioProcessing = false,
                                portfolioErrorMessage =
                                    error.message ?: MESSAGE_PORTFOLIO_SYSTEM_FAILED,
                                portfolioUploadProgress = 0,
                            )
                        }
                        return
                    }
                when (status.status) {
                    PortfolioStatus.READY -> {
                        readyPortfolioId = portfolioId
                        reduce {
                            copy(
                                isPortfolioProcessing = false,
                                portfolioUploadProgress = PORTFOLIO_PROGRESS_COMPLETE,
                            )
                        }
                        return
                    }

                    PortfolioStatus.FAILED_FILE -> {
                        reduce {
                            copy(
                                isPortfolioProcessing = false,
                                portfolioFileName = null,
                                portfolioErrorMessage = MESSAGE_PDF_UNREADABLE,
                                portfolioUploadProgress = 0,
                            )
                        }
                        return
                    }

                    PortfolioStatus.FAILED_SYSTEM -> {
                        reduce {
                            copy(
                                isPortfolioProcessing = false,
                                portfolioFileName = null,
                                portfolioErrorMessage = MESSAGE_PORTFOLIO_SYSTEM_FAILED,
                                portfolioUploadProgress = 0,
                            )
                        }
                        return
                    }

                    else -> {
                        // 폴링 1회 완료마다 10%씩 상승시키되, 완료 응답(100%)과 구분되도록
                        // 대기 구간은 90%에서 캡한다.
                        val nextProgress =
                            ((attempt + 1) * PORTFOLIO_PROGRESS_STEP_PER_POLL)
                                .coerceAtMost(PORTFOLIO_PROGRESS_POLLING_CAP)
                        reduce { copy(portfolioUploadProgress = nextProgress) }
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }
            reduce {
                copy(
                    isPortfolioProcessing = false,
                    portfolioErrorMessage = MESSAGE_PORTFOLIO_SYSTEM_FAILED,
                    portfolioUploadProgress = 0,
                )
            }
        }

        private fun advanceStep() {
            if (state.value.step == OnBoardingInterviewStep.JobDescription &&
                state.value.jdLinkStatus == JdLinkStatus.Validating
            ) {
                jdValidationJob?.cancel()
            }
            val next =
                when (state.value.step) {
                    OnBoardingInterviewStep.JobDescription -> OnBoardingInterviewStep.Portfolio
                    OnBoardingInterviewStep.Portfolio -> OnBoardingInterviewStep.MainProject
                    OnBoardingInterviewStep.MainProject -> OnBoardingInterviewStep.Preload
                    else -> return
                }
            reduce {
                copy(
                    step = next,
                    errorMessage = null,
                    jdLinkStatus =
                        if (jdLinkStatus ==
                            JdLinkStatus.Validating
                        ) {
                            JdLinkStatus.Idle
                        } else {
                            jdLinkStatus
                        },
                )
            }
            if (next == OnBoardingInterviewStep.Preload) {
                startPreload()
            }
        }

        /**
         * READY 포트폴리오가 있는 사용자가 Portfolio 스텝에 진입했을 때 Case1/Case2 안내를 1회만 노출한다.
         * load() 완료 시점에도 Portfolio 스텝이면 동일 로직을 재시도한다.
         */
        private fun shouldShowExistingPortfolioNotice(current: OnBoardingInterviewState): Boolean =
            !current.hasShownExistingPortfolioNotice &&
                existingPortfolioId != null &&
                current.step == OnBoardingInterviewStep.Portfolio &&
                current.existingPortfolioModalPhase == ExistingPortfolioModalPhase.None

        private fun maybeShowExistingPortfolioNotice() {
            val current = state.value
            if (shouldShowExistingPortfolioNotice(current)) {
                val phase =
                    if (existingReplaceAvailable && existingDeleteAvailable) {
                        ExistingPortfolioModalPhase.ConfirmContinue
                    } else {
                        ExistingPortfolioModalPhase.AutoDismissNotice
                    }
                val deleteAvailableSnapshot = existingDeleteAvailable
                reduce {
                    copy(
                        existingPortfolioModalPhase = phase,
                        hasShownExistingPortfolioNotice = true,
                        existingDeleteAvailable = deleteAvailableSnapshot,
                    )
                }
            }
        }

        private fun dismissAutoNoticeAndAdvance() {
            if (state.value.existingPortfolioModalPhase !=
                ExistingPortfolioModalPhase.AutoDismissNotice
            ) {
                return
            }
            reduce { copy(existingPortfolioModalPhase = ExistingPortfolioModalPhase.None) }
            advanceStep()
        }

        private fun startPreload() {
            val role = jobRole
            val years = careerYears
            val portfolioId = readyPortfolioId
            if (role == null || years == null || portfolioId == null) {
                reduce { copy(errorMessage = "필요한 정보를 불러오지 못했어요. 다시 시도해 주세요.") }
                return
            }

            reduce { copy(loadingBasicInfo = OnBoardingLoadingStepStatus.InProgress) }
            viewModelScope.launch {
                // 서버는 직군·연차를 요청 바디로 받지 않고 저장된 프로필 스냅샷을 사용한다.
                // 그래도 프로필이 미완성이면 세션 생성이 튕겨나오므로 위 role/years 가드는 유지한다.
                val request =
                    InterviewSessionRequest(
                        portfolioId = portfolioId,
                        jdUrl = jdUrl,
                        jdText = jdText,
                        freeText = state.value.mainProjectText.takeIf { it.isNotEmpty() },
                    )
                makeInterviewSession(request)
                    .onSuccess { result ->
                        // Interview 화면은 sessionId를 nav 인자가 아니라 로컬 InterviewProgress에서
                        // 조회하므로, 폴링을 시작하기 전에 먼저 저장해 두어야 한다.
                        saveInterviewSessionProgress(result.sessionId)
                        reduce {
                            copy(
                                loadingBasicInfo = OnBoardingLoadingStepStatus.Completed,
                                loadingJd = OnBoardingLoadingStepStatus.InProgress,
                                loadingPortfolio = OnBoardingLoadingStepStatus.InProgress,
                            )
                        }
                        pollInterviewSession(result.sessionId)
                    }.onFailure { error ->
                        handleSessionCreateFailure(error)
                    }
            }
        }

        /**
         * S3.5 서버 연관성 판단 실패는 일반 에러와 다르게 처리한다:
         * - 1~3회: MainProject 스텝으로 되돌리고 인라인 에러로 재입력을 유도한다.
         * - 4회째: 다이얼로그로 "포폴 다시 올리기 / 집중 프로젝트 없이 진행" 두 선택지를 제시한다.
         *   그 외 실패는 기존과 동일하게 상단 에러 메시지로 노출한다.
         */
        private fun handleSessionCreateFailure(error: Throwable) {
            when (error) {
                // 게이트1(계정 정지): 인라인 에러가 아니라 전용 안내 화면으로 보낸다.
                is AccountSuspendedException -> {
                    reduce {
                        copy(
                            loadingBasicInfo = OnBoardingLoadingStepStatus.Waiting,
                            loadingJd = OnBoardingLoadingStepStatus.Waiting,
                            loadingPortfolio = OnBoardingLoadingStepStatus.Waiting,
                        )
                    }
                    sendEffect(OnBoardingInterviewEffect.NavigateToSuspensionNotice)
                }

                is FreeTextNotRelevantException -> {
                    val nextCount = state.value.mainProjectRelevanceFailCount + 1
                    // 4회째부터는 이스케이프 다이얼로그를 함께 띄운다.
                    // 두 브랜치의 나머지 필드가 동일해서 showRelevanceFailDialog 한 값만 계산해 통합한다.
                    val showEscape = nextCount >= RELEVANCE_FAIL_ESCAPE_THRESHOLD
                    reduce {
                        copy(
                            step = OnBoardingInterviewStep.MainProject,
                            showRelevanceFailDialog = showEscape,
                            mainProjectRelevanceFailCount = nextCount,
                            mainProjectError = MESSAGE_FREETEXT_NOT_RELEVANT,
                            loadingBasicInfo = OnBoardingLoadingStepStatus.Waiting,
                            loadingJd = OnBoardingLoadingStepStatus.Waiting,
                            loadingPortfolio = OnBoardingLoadingStepStatus.Waiting,
                            errorMessage = null,
                        )
                    }
                }

                else -> {
                    reduce {
                        copy(
                            errorMessage = error.message,
                            loadingBasicInfo = OnBoardingLoadingStepStatus.Waiting,
                            loadingJd = OnBoardingLoadingStepStatus.Waiting,
                            loadingPortfolio = OnBoardingLoadingStepStatus.Waiting,
                        )
                    }
                }
            }
        }

        @Suppress("detekt:ReturnCount")
        private suspend fun pollInterviewSession(sessionId: Long) {
            repeat(MAX_POLL_ATTEMPTS) {
                val status =
                    getInterviewSession(sessionId).getOrElse { error ->
                        reduce { copy(errorMessage = error.message) }
                        return
                    }
                when (status.status) {
                    InterviewSessionStatusType.READY -> {
                        // 세 스텝을 모두 Completed로 마킹하면 Preload 화면이 초록 확장
                        // + 완료 텍스트 fadeIn/scale 애니메이션을 재생한다. 이 재생 시간을
                        // 사용자가 볼 수 있게 잠깐 대기한 후 결과 화면으로 이동한다.
                        reduce {
                            copy(
                                loadingBasicInfo = OnBoardingLoadingStepStatus.Completed,
                                loadingJd = OnBoardingLoadingStepStatus.Completed,
                                loadingPortfolio = OnBoardingLoadingStepStatus.Completed,
                            )
                        }
                        delay(PRELOAD_COMPLETION_DWELL_MS)
                        sendEffect(OnBoardingInterviewEffect.NavigateToResult(sessionId))
                        return
                    }

                    InterviewSessionStatusType.FAILED -> {
                        reduce { copy(errorMessage = "면접 준비에 실패했어요. 다시 시도해 주세요.") }
                        return
                    }

                    else -> {
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }
            reduce { copy(errorMessage = "면접 준비가 지연되고 있어요. 잠시 후 다시 시도해 주세요.") }
        }

        private fun onPreviousClick() {
            val previous =
                when (state.value.step) {
                    OnBoardingInterviewStep.JobDescription -> {
                        sendEffect(OnBoardingInterviewEffect.CloseRequested)
                        return
                    }

                    OnBoardingInterviewStep.Portfolio -> {
                        OnBoardingInterviewStep.JobDescription
                    }

                    OnBoardingInterviewStep.MainProject -> {
                        OnBoardingInterviewStep.Portfolio
                    }

                    OnBoardingInterviewStep.Preload -> {
                        // Preload 스텝에선 뒤로가기를 봉쇄한다.
                        return
                    }
                }
            reduce { copy(step = previous, errorMessage = null) }
        }

        /** PDF 검증 결과를 사용자 메시지로 변환한다. [PdfValidationResult.Valid]이면 null. */
        private fun PdfValidationResult.toErrorMessageOrNull(): String? =
            when (this) {
                is PdfValidationResult.Valid -> {
                    null
                }

                is PdfValidationResult.Invalid -> {
                    when (reason) {
                        PdfInvalidReason.INVALID_FILE_SIZE -> MESSAGE_PDF_SIZE

                        PdfInvalidReason.INVALID_PAGE_COUNT -> MESSAGE_PDF_PAGE

                        PdfInvalidReason.PASSWORD_REQUIRED -> MESSAGE_PDF_PASSWORD

                        PdfInvalidReason.UNKNOWN_FILE_SIZE,
                        PdfInvalidReason.NOT_SEEKABLE,
                        PdfInvalidReason.INVALID_PDF_FORMAT,
                        -> MESSAGE_PDF_CORRUPT
                    }
                }

                is PdfValidationResult.Error -> {
                    MESSAGE_PDF_CORRUPT
                }
            }

        private companion object {
            const val POLL_INTERVAL_MS = 3_000L
            const val MAX_POLL_ATTEMPTS = 40

            /** 포트폴리오 업로드 진행률: 폴링 1회당 상승 폭. */
            const val PORTFOLIO_PROGRESS_STEP_PER_POLL = 10

            /** 폴링 대기 구간 진행률 캡. READY 응답 전에는 이 값을 넘지 않는다. */
            const val PORTFOLIO_PROGRESS_POLLING_CAP = 90

            /** READY 응답을 받아 업로드 완료를 표시할 때의 최종 진행률. */
            const val PORTFOLIO_PROGRESS_COMPLETE = 100

            /**
             * 세션 준비 완료 후 결과 화면으로 넘어가기 전 사용자가 완료 애니메이션
             * (초록 배경 확장 + 완료 텍스트 fadeIn/scale)을 볼 수 있게 잠시 대기한다.
             */
            const val PRELOAD_COMPLETION_DWELL_MS = 1_800L
            const val HTTPS_SCHEME = "https://"
            const val MESSAGE_LINK_FORMAT = "올바른 URL 형식이 아니에요."
            const val MESSAGE_LINK_INVALID = "공고 내용을 정리하는 데 실패했어요. 공고 내용을 직접 붙여넣어 주세요."
            const val MESSAGE_LINK_RATE_LIMIT = "공고 링크는 하루에 5번까지만 입력할 수 있어요"
            const val JD_TEXT_MIN_LENGTH = 200
            const val JD_TEXT_MAX_LENGTH = 3000
            const val MESSAGE_TEXT_TOO_SHORT = "공고 내용은 200자 이상으로 입력해 주세요"
            const val FREETEXT_MIN_LENGTH = 10
            const val FREETEXT_MAX_LENGTH = 300
            const val MESSAGE_FREETEXT_TOO_SHORT = "집중 프로젝트 설명은 10자 이상 입력해 주세요"
            const val MESSAGE_FREETEXT_NOT_RELEVANT =
                "포트폴리오에서 그 내용을 찾지 못했어요. 포트폴리오에 있는 프로젝트로 다시 적어주세요"

            /** 연관성 실패가 이 횟수에 도달하면 재선택 다이얼로그로 이스케이프 경로를 제공한다. */
            const val RELEVANCE_FAIL_ESCAPE_THRESHOLD = 4
            const val MESSAGE_PORTFOLIO_REQUIRED = "포트폴리오를 업로드해주세요"
            const val MESSAGE_PDF_SIZE = "파일이 너무 커요. 20MB 이하 PDF로 올려주세요"
            const val MESSAGE_PDF_PAGE = "페이지가 너무 많아요. 30페이지 이하 PDF로 올려주세요"
            const val MESSAGE_PDF_PASSWORD = "암호가 걸린 PDF는 열 수 없어요. 암호를 푼 PDF로 올려주세요"
            const val MESSAGE_PDF_CORRUPT = "파일이 손상된 것 같아요. 파일을 확인하고 다시 시도해 주세요"

            // 서버 status가 FAILED_FILE로 오는 케이스(스캔본·글자 30자 미만 등) 전용 안내.
            const val MESSAGE_PDF_UNREADABLE =
                "이 PDF에서 글자를 읽지 못했어요. 스캔본·이미지로 만든 PDF는 인식이 어려워요. " +
                    "글자가 드래그로 선택되는 PDF로 다시 올려주세요"

            // 서버 status가 FAILED_SYSTEM/폴링 타임아웃일 때 안내.
            const val MESSAGE_PORTFOLIO_SYSTEM_FAILED =
                "포트폴리오 분석이 예상보다 오래 걸리고 있어요. 잠시 후 다시 시도해 주세요"
        }
    }
