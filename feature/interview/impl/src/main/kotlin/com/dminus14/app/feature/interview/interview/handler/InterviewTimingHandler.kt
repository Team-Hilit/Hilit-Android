package com.dminus14.app.feature.interview.interview.handler

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.model.InterviewAnswerEndRequest
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.usecase.GetInterviewElapsedTimeUseCase
import com.dminus14.app.domain.usecase.StartInterviewTimerUseCase
import com.dminus14.app.feature.interview.InterviewConstants
import com.dminus14.app.feature.interview.interview.InterviewEffect
import com.dminus14.app.feature.interview.interview.InterviewIntent
import com.dminus14.app.feature.interview.interview.InterviewMutation
import com.dminus14.app.feature.interview.interview.InterviewScreenState
import com.dminus14.app.feature.interview.interview.InterviewState
import com.dminus14.app.feature.interview.interview.InterviewTimerCoordinator
import com.dminus14.app.feature.interview.interview.InterviewWorkflow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 경과 시간을 갱신하고 면접 시간 경계 사건을 전달한다. */
internal class InterviewTimingHandler(
    private val context: MviContext<InterviewState, InterviewMutation, InterviewEffect>,
    private val startTimer: StartInterviewTimerUseCase,
    private val getElapsedTime: GetInterviewElapsedTimeUseCase,
    private val timerCoordinator: InterviewTimerCoordinator,
    private val workflow: (InterviewWorkflow) -> Unit,
) {
    private val state get() = context.state
    private var startJob: Job? = null
    private var timerJob: Job? = null
    private var restoredHardCapReached = false

    fun handle(intent: InterviewIntent.Timing) {
        when (intent) {
            InterviewIntent.Timing.StartInterview -> {
                startInterview()
            }

            is InterviewIntent.Timing.UpdateElapsedTime -> {
                updateElapsedTime(intent.elapsedMillis)
            }

            InterviewIntent.Timing.ReportHardCapReached -> {
                workflow(InterviewWorkflow.RequestEnd(InterviewAnswerEndRequest.HardCap))
            }
        }
    }

    @Suppress("ReturnCount")
    private fun startInterview() {
        if (startJob?.isActive == true) return
        val current = state.value
        if (current.screenState != InterviewScreenState.START_GUIDE ||
            !current.isReadyToStart
        ) {
            return
        }
        val sessionId = current.sessionId ?: return
        val questionId = current.questionId ?: return
        startJob =
            context.launch {
                startTimer()
                context.dispatch(InterviewMutation.InterviewStarted)
                context.sendEffect(
                    InterviewEffect.StartRecordingSegment(
                        sessionId = sessionId,
                        type = InterviewMediaSegmentType.QUESTION_VIDEO,
                        questionId = questionId,
                        startedAtMillis = state.value.elapsedMillis,
                    ),
                )
                workflow(InterviewWorkflow.PlayCurrentQuestion(true))
                startTicker()
            }
    }

    fun startTicker() {
        if (timerJob?.isActive == true) return
        timerJob =
            context.launch {
                while (isActive) {
                    updateElapsedTime(getElapsedTime())
                    delay(InterviewConstants.TIMER_TICK_INTERVAL_MILLIS)
                }
            }
    }

    private fun updateElapsedTime(elapsedMillis: Long) {
        val bounded =
            elapsedMillis.coerceIn(
                state.value.elapsedMillis,
                InterviewConstants.MAX_INTERVIEW_SECONDS * MILLIS_PER_SECOND,
            )
        context.dispatch(InterviewMutation.ElapsedTimeUpdated(bounded))
        timerCoordinator.update(bounded).forEach { event ->
            when (event) {
                InterviewTimerCoordinator.Event.EARLY_FINISH -> {
                    context.sendEffect(InterviewEffect.ShowEarlyFinishAvailable)
                }

                InterviewTimerCoordinator.Event.WRAP_UP -> {
                    Unit
                }

                InterviewTimerCoordinator.Event.COUNTDOWN -> {
                    context.sendEffect(InterviewEffect.PlayFinalCountdown)
                }

                InterviewTimerCoordinator.Event.HARD_CAP -> {
                    workflow(InterviewWorkflow.RequestEnd(InterviewAnswerEndRequest.HardCap))
                }
            }
        }
    }

    fun stopTicker() {
        timerJob?.cancel()
    }

    fun restoreTimer(elapsedMillis: Long) {
        restoredHardCapReached = timerCoordinator.restore(elapsedMillis)
    }

    fun reportRestoredHardCap() {
        if (restoredHardCapReached) {
            workflow(InterviewWorkflow.RequestEnd(InterviewAnswerEndRequest.HardCap))
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
