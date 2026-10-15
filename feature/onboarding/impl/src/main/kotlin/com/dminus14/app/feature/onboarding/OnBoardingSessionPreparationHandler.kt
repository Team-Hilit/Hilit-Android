package com.dminus14.app.feature.onboarding

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.AccountSuspendedException
import com.dminus14.app.domain.exception.FreeTextNotRelevantException
import com.dminus14.app.domain.model.InterviewSessionRequest
import com.dminus14.app.domain.model.InterviewSessionStatusType
import com.dminus14.app.domain.usecase.GetInterviewSessionUseCase
import com.dminus14.app.domain.usecase.MakeInterviewSessionUseCase
import com.dminus14.app.domain.usecase.SaveInterviewSessionProgressUseCase
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MAX_POLL_ATTEMPTS
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.POLL_INTERVAL_MS
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.PRELOAD_COMPLETION_DWELL_MS
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.RELEVANCE_FAIL_ESCAPE_THRESHOLD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.dminus14.app.feature.onboarding.OnBoardingMutation.Flow as FlowMutation
import com.dminus14.app.feature.onboarding.OnBoardingMutation.SessionPreparation as SessionMutation

/** 화면의 세션 생성과 준비 작업을 소유한다. */
internal class OnBoardingSessionPreparationHandler(
    private val context:
        MviContext<OnBoardingInterviewState, OnBoardingMutation, OnBoardingInterviewEffect>,
    private val makeInterviewSession: MakeInterviewSessionUseCase,
    private val getInterviewSession: GetInterviewSessionUseCase,
    private val saveInterviewSessionProgress: SaveInterviewSessionProgressUseCase,
) {
    private var isPreparing = false
    private var hasPreparedSession = false

    fun startPreload(
        jobRole: String?,
        careerYears: Int?,
        readyPortfolioId: String?,
        jdUrl: String?,
        jdText: String?,
    ) {
        if (isPreparing || hasPreparedSession) return
        val role = jobRole
        val years = careerYears
        val portfolioId = readyPortfolioId
        if (role == null || years == null || portfolioId == null) {
            context.dispatch(SessionMutation.ProfileMissing)
            return
        }

        isPreparing = true
        context.dispatch(SessionMutation.SessionCreationStarted)
        context.launch {
            try {
                // 서버는 직군·연차를 요청 바디로 받지 않고 저장된 프로필 스냅샷을 사용한다.
                // 그래도 프로필이 미완성이면 세션 생성이 튕겨나오므로 위 role/years 가드는 유지한다.
                val request =
                    InterviewSessionRequest(
                        portfolioId = portfolioId,
                        jdUrl = jdUrl,
                        jdText = jdText,
                        freeText =
                            context.state.value.mainProjectText
                                .takeIf { it.isNotEmpty() },
                    )
                makeInterviewSession(request)
                    .onSuccess { result ->
                        // Interview 화면은 sessionId를 nav 인자가 아니라 로컬 InterviewProgress에서
                        // 조회하므로, 폴링을 시작하기 전에 먼저 저장해 두어야 한다.
                        saveInterviewSessionProgress(result.sessionId)
                        context.dispatch(SessionMutation.SessionCreated)
                        pollInterviewSession(result.sessionId)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        handleSessionCreateFailure(error)
                    }
            } finally {
                isPreparing = false
            }
        }
    }

    private fun handleSessionCreateFailure(error: Throwable) {
        when (error) {
            // 게이트1(계정 정지): 인라인 에러가 아니라 전용 안내 화면으로 보낸다.
            is AccountSuspendedException -> {
                context.dispatch(SessionMutation.AccountSuspended)
                context.sendEffect(OnBoardingInterviewEffect.NavigateToSuspensionNotice)
            }

            is FreeTextNotRelevantException -> {
                val nextCount = context.state.value.mainProjectRelevanceFailCount + 1
                // 4회째부터는 이스케이프 다이얼로그를 함께 띄운다.
                // 두 브랜치의 나머지 필드가 동일해서 showRelevanceFailDialog 한 값만 계산해 통합한다.
                val showEscape = nextCount >= RELEVANCE_FAIL_ESCAPE_THRESHOLD
                context.dispatch(
                    SessionMutation.RelevanceRejected(nextCount, showEscape),
                )
            }

            else -> {
                context.dispatch(
                    SessionMutation.SessionCreationFailed(error.message),
                )
            }
        }
    }

    @Suppress("ReturnCount") // READY와 실패 응답은 폴링을 즉시 종료한다.
    private suspend fun pollInterviewSession(sessionId: Long) {
        repeat(MAX_POLL_ATTEMPTS) {
            val status =
                getInterviewSession(sessionId).getOrElse { error ->
                    if (error is CancellationException) throw error
                    context.dispatch(FlowMutation.ErrorReported(error.message))
                    return
                }
            when (status.status) {
                InterviewSessionStatusType.READY -> {
                    // 세 스텝을 모두 Completed로 마킹하면 Preload 화면이 초록 확장
                    // + 완료 텍스트 fadeIn/scale 애니메이션을 재생한다. 이 재생 시간을
                    // 사용자가 볼 수 있게 잠깐 대기한 후 결과 화면으로 이동한다.
                    hasPreparedSession = true
                    context.dispatch(SessionMutation.SessionReady)
                    delay(PRELOAD_COMPLETION_DWELL_MS)
                    context.sendEffect(OnBoardingInterviewEffect.NavigateToResult(sessionId))
                    return
                }

                InterviewSessionStatusType.FAILED -> {
                    context.dispatch(SessionMutation.SessionFailed)
                    return
                }

                else -> {
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        context.dispatch(SessionMutation.SessionTimedOut)
    }
}
