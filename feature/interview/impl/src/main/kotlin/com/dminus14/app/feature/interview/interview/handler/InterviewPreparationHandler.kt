package com.dminus14.app.feature.interview.interview.handler

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.InterviewSessionStatusType
import com.dminus14.app.domain.usecase.CleanupExpiredInterviewDataUseCase
import com.dminus14.app.domain.usecase.DeleteInterviewSessionUseCase
import com.dminus14.app.domain.usecase.GetInterviewElapsedTimeUseCase
import com.dminus14.app.domain.usecase.GetInterviewProgressUseCase
import com.dminus14.app.domain.usecase.GetInterviewSessionUseCase
import com.dminus14.app.feature.interview.InterviewConstants
import com.dminus14.app.feature.interview.api.InterviewErrorType
import com.dminus14.app.feature.interview.interview.InterviewCompletionReason
import com.dminus14.app.feature.interview.interview.InterviewEffect
import com.dminus14.app.feature.interview.interview.InterviewIntent
import com.dminus14.app.feature.interview.interview.InterviewMutation
import com.dminus14.app.feature.interview.interview.InterviewScreenState
import com.dminus14.app.feature.interview.interview.InterviewState
import com.dminus14.app.feature.interview.interview.InterviewWorkflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** 권한·장치 점검과 최소 준비 시간, 서버 준비 폴링을 관리한다. */
@Suppress("LongParameterList")
internal class InterviewPreparationHandler(
    private val context: MviContext<InterviewState, InterviewMutation, InterviewEffect>,
    private val cleanupExpiredData: CleanupExpiredInterviewDataUseCase,
    private val getProgress: GetInterviewProgressUseCase,
    private val getSession: GetInterviewSessionUseCase,
    private val getElapsedTime: GetInterviewElapsedTimeUseCase,
    private val deleteSession: DeleteInterviewSessionUseCase,
    private val workflow: (InterviewWorkflow) -> Unit,
) {
    private val state get() = context.state
    private var preparationJob: Job? = null
    private var preparationStageJob: Job? = null
    private var hasLoaded = false
    private var isDeviceCheckMinDurationElapsed = false
    private var isQuestionPreparingMinDurationElapsed = false

    fun handle(intent: InterviewIntent.Preparation) {
        when (intent) {
            InterviewIntent.Preparation.LoadInterview -> {
                loadInterview()
            }

            InterviewIntent.Preparation.CheckCameraPermission,
            InterviewIntent.Preparation.ClickRetryDeviceCheck,
            -> {
                context.sendEffect(InterviewEffect.RequestCameraPermission)
            }

            InterviewIntent.Preparation.ClickOpenSettings -> {
                state.value.permanentlyDeniedPermission?.let {
                    context.sendEffect(InterviewEffect.OpenAppSettings(it))
                }
            }

            InterviewIntent.Preparation.ReportCameraPermissionGranted -> {
                context.dispatch(InterviewMutation.CameraPermissionGranted)
                context.sendEffect(InterviewEffect.RequestMicrophonePermission)
            }

            InterviewIntent.Preparation.ReportCameraReady -> {
                context.dispatch(InterviewMutation.CameraReady)
                updatePreparationGate()
                workflow(InterviewWorkflow.TryApplyPendingRecovery)
            }

            is InterviewIntent.Preparation.ReportCameraPermissionDenied -> {
                context.dispatch(InterviewMutation.CameraPermissionDenied(intent.permanentlyDenied))
            }

            InterviewIntent.Preparation.ReportCameraBindingFailure -> {
                context.sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.MIC_DEVICE))
            }

            InterviewIntent.Preparation.ReportMicrophoneReady -> {
                context.dispatch(InterviewMutation.MicrophoneReady)
                updatePreparationGate()
                workflow(InterviewWorkflow.TryApplyPendingRecovery)
            }

            is InterviewIntent.Preparation.ReportMicrophonePermissionDenied -> {
                context.dispatch(
                    InterviewMutation.MicrophonePermissionDenied(intent.permanentlyDenied),
                )
            }

            InterviewIntent.Preparation.ReportMicrophoneFailure -> {
                context.sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.MIC_DEVICE))
            }

            is InterviewIntent.Preparation.ReportStorageAvailability -> {
                onStorageAvailable(intent.availableBytes)
            }

            InterviewIntent.Preparation.ClickPermissionDeniedBack -> {
                clearBeforeFirstQuestion(InterviewEffect.PermissionDeniedExitRequested)
            }
        }
    }

    private fun loadInterview() {
        if (hasLoaded) {
            workflow(InterviewWorkflow.ConsumeRecoveryResult)
            return
        }
        hasLoaded = true
        startDeviceCheckMinimumDuration()
        context.launch {
            cleanupExpiredData()
            val progress = getProgress()
            if (progress == null) {
                context.sendEffect(InterviewEffect.PrerequisiteMissing)
                return@launch
            }
            context.dispatch(InterviewMutation.SessionLoaded(progress.sessionId))
            if (progress.timerStartedAtEpochMillis != null) {
                val elapsedMillis =
                    getElapsedTime().coerceIn(
                        0L,
                        InterviewConstants.MAX_INTERVIEW_SECONDS * MILLIS_PER_SECOND,
                    )
                workflow(InterviewWorkflow.RestoreTimer(elapsedMillis))
                context.dispatch(InterviewMutation.ElapsedTimeUpdated(elapsedMillis))
                workflow(InterviewWorkflow.StartTicker)
                context.sendEffect(InterviewEffect.RequestCameraPermission)
                context.sendEffect(InterviewEffect.CheckStorageAvailability)
                context.sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.NETWORK))
                workflow(InterviewWorkflow.CheckRestoredHardCap)

                return@launch
            }
            context.sendEffect(InterviewEffect.RequestCameraPermission)
            context.sendEffect(InterviewEffect.CheckStorageAvailability)
            startPreparationPolling(progress.sessionId)
        }
    }

    private fun startPreparationPolling(sessionId: Long) {
        preparationJob?.cancel()
        preparationJob =
            context.launch {
                while (isActive) {
                    val sessionResult = getSession(sessionId)
                    currentCoroutineContext().ensureActive()
                    val failure = sessionResult.exceptionOrNull()
                    if (failure is NetworkUnavailableException) {
                        delay(InterviewConstants.SESSION_POLL_INTERVAL_MILLIS)
                        continue
                    }
                    if (failure != null) {
                        handleFailure(failure, InterviewErrorType.NETWORK)
                        return@launch
                    }
                    val status = sessionResult.getOrThrow()
                    when (status.status) {
                        InterviewSessionStatusType.READY -> {
                            val questionId = status.summaryQuestion?.questionId
                            if (questionId == null) {
                                clearBeforeFirstQuestion(InterviewEffect.PrerequisiteMissing)
                            } else {
                                context.dispatch(InterviewMutation.QuestionPrepared(questionId))
                                updatePreparationGate()
                            }
                            return@launch
                        }

                        InterviewSessionStatusType.PROCESSING -> {
                            Unit
                        }

                        InterviewSessionStatusType.FAILED -> {
                            clearBeforeFirstQuestion(InterviewEffect.PrerequisiteMissing)
                            return@launch
                        }

                        is InterviewSessionStatusType.Unknown -> {
                            workflow(InterviewWorkflow.GlobalError(GlobalAppEvent.ShowUnknownError))
                            return@launch
                        }
                    }
                    delay(InterviewConstants.SESSION_POLL_INTERVAL_MILLIS)
                }
            }
    }

    private fun onStorageAvailable(availableBytes: Long) {
        context.dispatch(InterviewMutation.StorageChecked(availableBytes))
        updatePreparationGate()
        workflow(InterviewWorkflow.TryApplyPendingRecovery)
    }

    private fun updatePreparationGate() {
        val current = state.value
        if (!current.isReadyToStart) return

        when (current.screenState) {
            InterviewScreenState.DEVICE_CHECK -> {
                if (isDeviceCheckMinDurationElapsed) {
                    context.dispatch(InterviewMutation.QuestionPreparationStarted)
                    startQuestionPreparingMinimumDuration()
                }
            }

            InterviewScreenState.QUESTION_PREPARING -> {
                if (isQuestionPreparingMinDurationElapsed) {
                    context.dispatch(InterviewMutation.StartGuideReady)
                }
            }

            else -> {
                Unit
            }
        }
    }

    private fun startDeviceCheckMinimumDuration() {
        preparationStageJob?.cancel()
        isDeviceCheckMinDurationElapsed = false
        isQuestionPreparingMinDurationElapsed = false
        preparationStageJob =
            context.launch {
                delay(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS)
                isDeviceCheckMinDurationElapsed = true
                preparationStageJob = null
                updatePreparationGate()
            }
    }

    private fun startQuestionPreparingMinimumDuration() {
        preparationStageJob?.cancel()
        isQuestionPreparingMinDurationElapsed = false
        preparationStageJob =
            context.launch {
                delay(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS)
                isQuestionPreparingMinDurationElapsed = true
                preparationStageJob = null
                updatePreparationGate()
            }
    }

    /** 첫 질문 전 서버 정리 API가 없어 승인된 기존 로컬 정리 동작을 유지한다. */
    fun clearBeforeFirstQuestion(completionEffect: InterviewEffect? = null) {
        val sessionId = state.value.sessionId ?: return
        preparationJob?.cancel()
        preparationJob = null
        preparationStageJob?.cancel()
        preparationStageJob = null
        context.launch {
            deleteSession(sessionId)
            context.sendEffect(
                completionEffect
                    ?: InterviewEffect.InterviewEnded(
                        InterviewCompletionReason.ABANDONED,
                        sessionId,
                    ),
            )
        }
    }

    private fun handleFailure(
        error: Throwable,
        recoverableType: InterviewErrorType,
    ) {
        if (error is CancellationException) throw error
        when (error) {
            is NetworkUnavailableException -> {
                context.sendEffect(
                    InterviewEffect.NavigateToError(recoverableType),
                )
            }

            is ServerException -> {
                workflow(InterviewWorkflow.GlobalError(GlobalAppEvent.ShowServerErrorAndExit))
            }

            else -> {
                workflow(InterviewWorkflow.GlobalError(GlobalAppEvent.ShowUnknownError))
            }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
