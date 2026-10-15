package com.dminus14.app.feature.onboarding

import android.content.Context
import android.net.Uri
import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.core.common.pdf.PdfInvalidReason
import com.dminus14.app.core.common.pdf.PdfValidationResult
import com.dminus14.app.core.common.pdf.validatePdf
import com.dminus14.app.domain.model.PortfolioStatus
import com.dminus14.app.domain.usecase.DeletePortfolioUseCase
import com.dminus14.app.domain.usecase.GetPortfolioOverviewUseCase
import com.dminus14.app.domain.usecase.GetPortfolioStatusUseCase
import com.dminus14.app.domain.usecase.UploadPortfolioUseCase
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MAX_POLL_ATTEMPTS
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_PDF_CORRUPT
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_PDF_PAGE
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_PDF_PASSWORD
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_PDF_SIZE
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_PDF_UNREADABLE
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_PORTFOLIO_SYSTEM_FAILED
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.POLL_INTERVAL_MS
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.PORTFOLIO_PROGRESS_POLLING_CAP
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.PORTFOLIO_PROGRESS_STEP_PER_POLL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.Portfolio as PortfolioIntent
import com.dminus14.app.feature.onboarding.OnBoardingMutation.Flow as FlowMutation
import com.dminus14.app.feature.onboarding.OnBoardingMutation.Portfolio as PortfolioMutation

/** 화면 실행 문맥과 PDF 검증·업로드·삭제 의존성을 한 작업 소유자에 조립한다. */
@Suppress("LongParameterList")
internal class OnBoardingPortfolioHandler(
    private val context:
        MviContext<OnBoardingInterviewState, OnBoardingMutation, OnBoardingInterviewEffect>,
    private val androidContext: Context,
    private val getPortfolioOverview: GetPortfolioOverviewUseCase,
    private val uploadPortfolio: UploadPortfolioUseCase,
    private val getPortfolioStatus: GetPortfolioStatusUseCase,
    private val deletePortfolio: DeletePortfolioUseCase,
    private val ioDispatcher: CoroutineDispatcher,
    private val advanceStep: () -> Unit,
) {
    fun onIntent(intent: PortfolioIntent) {
        when (intent) {
            PortfolioIntent.ClickPortfolioUpload -> {
                context.sendEffect(OnBoardingInterviewEffect.LaunchPortfolioPicker)
            }

            PortfolioIntent.ClickPortfolioRemove -> {
                removePortfolio()
            }

            PortfolioIntent.PortfolioStepEntered -> {
                maybeShowExistingPortfolioNotice()
            }

            PortfolioIntent.ClickExistingPortfolioConfirm -> {
                context.dispatch(
                    PortfolioMutation.PortfolioNoticeDismissed,
                )
            }

            PortfolioIntent.ClickAutoDismissNotice,
            PortfolioIntent.AutoDismissNoticeTimedOut,
            -> {
                dismissAutoNoticeAndAdvance()
            }

            is PortfolioIntent.PortfolioFileSelected -> {
                startPortfolioUpload(intent)
            }
        }
    }

    var readyPortfolioId: String? = null
        private set
    private var existingPortfolioId: String? = null
    private var existingPortfolioFileName: String? = null
    private var existingReplaceAvailable = false
    private var existingDeleteAvailable = false
    private var operationJob: Job? = null
    private var overviewJob: Job? = null
    private var operationGeneration = 0L
    private var overviewGeneration = 0L
    private var activeUploadFile: File? = null

    private fun isProcessing(): Boolean =
        context.state.value.isPortfolioProcessing || operationJob?.isActive == true

    fun loadOverview() {
        if (isProcessing()) return
        overviewJob?.cancel()
        val generation = ++overviewGeneration
        val operation = operationGeneration
        overviewJob =
            context.launch {
                getPortfolioOverview()
                    .onSuccess { overview ->
                        if (generation != overviewGeneration || operation != operationGeneration) {
                            return@onSuccess
                        }
                        val portfolio = overview.portfolio
                        existingPortfolioId = portfolio?.portfolioId
                        existingPortfolioFileName = portfolio?.fileName
                        existingReplaceAvailable = overview.isReplaceAvailable
                        existingDeleteAvailable = overview.isDeleteAvailable
                        // 재진입 시 기존 READY 포트폴리오는 즉시 노출·재사용한다.
                        // (스펙 S2: "정상 저장(READY) 포폴은 이탈해도 보존, 다음 진입 시 재사용")
                        if (portfolio != null && portfolio.status == PortfolioStatus.READY) {
                            readyPortfolioId = portfolio.portfolioId
                            context.dispatch(
                                PortfolioMutation.ExistingPortfolioLoaded(portfolio.fileName),
                            )
                        }
                        maybeShowExistingPortfolioNotice()
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (generation != overviewGeneration || operation != operationGeneration) {
                            return@onFailure
                        }
                        // 조회 실패를 무음 처리하면 사용자에게는 "포폴 없음"으로 보여, 재업로드 시
                        // 서버가 PORTFOLIO_ALREADY_EXISTS로 튕겨내는 혼란을 만든다.
                        // checkUserProfile 실패와 동일하게 errorMessage로 노출한다.
                        context.dispatch(FlowMutation.ErrorReported(error.message))
                    }
            }
    }

    fun removePortfolio() {
        if (isProcessing()) return
        val portfolioId = readyPortfolioId ?: return

        val generation = ++operationGeneration
        context.dispatch(PortfolioMutation.PortfolioDeletionStarted)
        operationJob =
            context.launch {
                deletePortfolio(portfolioId)
                    .onSuccess {
                        if (generation != operationGeneration) return@onSuccess
                        readyPortfolioId = null
                        existingPortfolioId = null
                        existingPortfolioFileName = null
                        context.dispatch(PortfolioMutation.PortfolioDeleted)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (generation != operationGeneration) return@onFailure
                        context.dispatch(
                            PortfolioMutation.PortfolioDeletionFailed(error.message),
                        )
                    }
            }
    }

    fun startPortfolioUpload(intent: PortfolioIntent.PortfolioFileSelected) {
        if (isProcessing()) {
            // 중복 선택 결과의 캐시만 정리하고 이미 전송 중인 파일은 유지한다.
            if (intent.file != activeUploadFile) intent.file.delete()
            return
        }
        val generation = ++operationGeneration
        activeUploadFile = intent.file
        context.dispatch(PortfolioMutation.PortfolioUploadStarted(intent.fileName))
        operationJob =
            context.launch {
                try {
                    val validation =
                        withContext(ioDispatcher) {
                            validatePdf(
                                androidContext.contentResolver,
                                Uri.fromFile(intent.file),
                            )
                        }
                    if (generation != operationGeneration) return@launch
                    val validationMessage = validation.toErrorMessageOrNull()
                    if (validationMessage != null) {
                        context.dispatch(
                            PortfolioMutation.PortfolioFileRejected(validationMessage),
                        )
                        return@launch
                    }

                    // 서버 스펙: pageCount 는 Positive int 필수. null 로 보내면 400.
                    // validatePdf 가 Valid 를 반환한 시점에 이미 pageCount 를 알고 있으므로 그대로 전달한다.
                    val pageCount = (validation as? PdfValidationResult.Valid)?.pageCount
                    uploadPortfolio(
                        file = intent.file,
                        fileName = intent.fileName,
                        pageCount = pageCount,
                    ).onSuccess { result ->
                        if (generation != operationGeneration) return@onSuccess
                        pollPortfolioStatus(result.portfolioId, generation)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (generation != operationGeneration) return@onFailure
                        // 서버 매핑된 예외 메시지(스펙 6장 문구)를 그대로 보여준다.
                        // 매핑되지 않은 실패는 시스템 실패 안내로 폴백한다.
                        context.dispatch(
                            PortfolioMutation.PortfolioFileRejected(
                                error.message ?: MESSAGE_PORTFOLIO_SYSTEM_FAILED,
                            ),
                        )
                    }
                } finally {
                    // 검증 실패·업로드 성공·실패·폴링·취소 어떤 경로든 캐시 사본은 정리한다.
                    // 폴링은 서버 sessionId만 사용하므로 이 시점 이후 로컬 file 참조는 없다.
                    intent.file.delete()
                    if (generation == operationGeneration) activeUploadFile = null
                }
            }
    }

    @Suppress("ReturnCount") // 완료·파일 실패·시스템 실패 응답은 폴링을 즉시 종료한다.
    private suspend fun pollPortfolioStatus(portfolioId: String, generation: Long) {
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            val status =
                getPortfolioStatus(portfolioId).getOrElse { error ->
                    if (error is CancellationException) throw error
                    context.dispatch(
                        PortfolioMutation.PortfolioPollingFailed(
                            error.message ?: MESSAGE_PORTFOLIO_SYSTEM_FAILED,
                        ),
                    )
                    return
                }
            if (generation != operationGeneration) return
            when (status.status) {
                PortfolioStatus.READY -> {
                    readyPortfolioId = portfolioId
                    context.dispatch(PortfolioMutation.PortfolioReady)
                    return
                }

                PortfolioStatus.FAILED_FILE -> {
                    context.dispatch(
                        PortfolioMutation.PortfolioFileRejected(MESSAGE_PDF_UNREADABLE),
                    )
                    return
                }

                PortfolioStatus.FAILED_SYSTEM -> {
                    context.dispatch(
                        PortfolioMutation.PortfolioFileRejected(MESSAGE_PORTFOLIO_SYSTEM_FAILED),
                    )
                    return
                }

                else -> {
                    // 폴링 1회 완료마다 10%씩 상승시키되, 완료 응답(100%)과 구분되도록
                    // 대기 구간은 90%에서 캡한다.
                    val nextProgress =
                        ((attempt + 1) * PORTFOLIO_PROGRESS_STEP_PER_POLL)
                            .coerceAtMost(PORTFOLIO_PROGRESS_POLLING_CAP)
                    context.dispatch(
                        PortfolioMutation.PortfolioPollingProgressed(nextProgress),
                    )
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        context.dispatch(
            PortfolioMutation.PortfolioPollingFailed(MESSAGE_PORTFOLIO_SYSTEM_FAILED),
        )
    }

    private fun shouldShowExistingPortfolioNotice(current: OnBoardingInterviewState): Boolean =
        !current.hasShownExistingPortfolioNotice &&
            existingPortfolioId != null &&
            current.step == OnBoardingInterviewStep.Portfolio &&
            current.existingPortfolioModalPhase == ExistingPortfolioModalPhase.None

    fun maybeShowExistingPortfolioNotice() {
        val current = context.state.value
        if (shouldShowExistingPortfolioNotice(current)) {
            val phase =
                if (existingReplaceAvailable && existingDeleteAvailable) {
                    ExistingPortfolioModalPhase.ConfirmContinue
                } else {
                    ExistingPortfolioModalPhase.AutoDismissNotice
                }
            val deleteAvailableSnapshot = existingDeleteAvailable
            context.dispatch(
                PortfolioMutation.PortfolioNoticeShown(phase, deleteAvailableSnapshot),
            )
        }
    }

    fun dismissAutoNoticeAndAdvance() {
        if (context.state.value.existingPortfolioModalPhase !=
            ExistingPortfolioModalPhase.AutoDismissNotice
        ) {
            return
        }
        context.dispatch(PortfolioMutation.PortfolioNoticeDismissed)
        advanceStep()
    }

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
}
