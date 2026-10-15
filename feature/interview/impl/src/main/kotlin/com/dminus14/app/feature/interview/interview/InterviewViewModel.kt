package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.usecase.CheckpointInterviewProgressUseCase
import com.dminus14.app.domain.usecase.CleanupExpiredInterviewDataUseCase
import com.dminus14.app.domain.usecase.CreateInterviewMediaSegmentUseCase
import com.dminus14.app.domain.usecase.DeleteInterviewMediaFileUseCase
import com.dminus14.app.domain.usecase.DeleteInterviewSessionUseCase
import com.dminus14.app.domain.usecase.FinalizeInterviewMediaSegmentUseCase
import com.dminus14.app.domain.usecase.GetInterviewElapsedTimeUseCase
import com.dminus14.app.domain.usecase.GetInterviewMediaManifestUseCase
import com.dminus14.app.domain.usecase.GetInterviewProgressUseCase
import com.dminus14.app.domain.usecase.GetInterviewSessionUseCase
import com.dminus14.app.domain.usecase.GetQuestionAudioStreamUrlUseCase
import com.dminus14.app.domain.usecase.SaveInterviewWrapUpRangeUseCase
import com.dminus14.app.domain.usecase.SavePendingInterviewAnswerUseCase
import com.dminus14.app.domain.usecase.StartInterviewTimerUseCase
import com.dminus14.app.domain.usecase.SubmitAnswerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 단일 State/Effect 수명을 소유하고 책임별 Handler의 실행 순서를 조정한다. */
@HiltViewModel
@Suppress("LongParameterList")
class InterviewViewModel
    @Inject
    constructor(
        cleanupExpiredData: CleanupExpiredInterviewDataUseCase,
        getProgress: GetInterviewProgressUseCase,
        getSession: GetInterviewSessionUseCase,
        startTimer: StartInterviewTimerUseCase,
        getElapsedTime: GetInterviewElapsedTimeUseCase,
        getMediaManifest: GetInterviewMediaManifestUseCase,
        checkpointProgress: CheckpointInterviewProgressUseCase,
        getQuestionAudioUrl: GetQuestionAudioStreamUrlUseCase,
        createMediaSegment: CreateInterviewMediaSegmentUseCase,
        finalizeMediaSegment: FinalizeInterviewMediaSegmentUseCase,
        submitAnswer: SubmitAnswerUseCase,
        deleteMediaFile: DeleteInterviewMediaFileUseCase,
        savePendingAnswer: SavePendingInterviewAnswerUseCase,
        saveWrapUpRange: SaveInterviewWrapUpRangeUseCase,
        deleteSession: DeleteInterviewSessionUseCase,
        recoveryStore: InterviewRecoveryStore,
        timerCoordinator: InterviewTimerCoordinator,
        turnStateMachine: InterviewTurnStateMachine,
        fatalExitPrompt: InterviewFatalExitPrompt,
    ) : MviViewModel<InterviewIntent, InterviewState, InterviewEffect>(InterviewState()) {
        private val context = createContext(InterviewReducer)
        private val preparation =
            InterviewPreparationHandler(
                context = context,
                cleanupExpiredData = cleanupExpiredData,
                getProgress = getProgress,
                getSession = getSession,
                getElapsedTime = getElapsedTime,
                deleteSession = deleteSession,
                workflow = ::onWorkflow,
            )
        private val timing =
            InterviewTimingHandler(
                context = context,
                startTimer = startTimer,
                getElapsedTime = getElapsedTime,
                timerCoordinator = timerCoordinator,
                workflow = ::onWorkflow,
            )
        private val submission =
            InterviewSubmissionHandler(
                context = context,
                submitAnswer = submitAnswer,
                savePendingAnswer = savePendingAnswer,
                deleteMediaFile = deleteMediaFile,
                turnStateMachine = turnStateMachine,
                workflow = ::onWorkflow,
            )
        private val turn =
            InterviewTurnHandler(
                context = context,
                getQuestionAudioUrl = getQuestionAudioUrl,
                finalizeMediaSegment = finalizeMediaSegment,
                getMediaManifest = getMediaManifest,
                createMediaSegment = createMediaSegment,
                workflow = ::onWorkflow,
                isConnectionInterrupted = { recovery.connectionInterrupted },
                pendingEndRequest = { submission.pendingEndRequest },
            )
        private val finalization =
            InterviewFinalizationHandler(
                context = context,
                saveWrapUpRange = saveWrapUpRange,
                workflow = ::onWorkflow,
            )
        private val recovery =
            InterviewRecoveryHandler(
                context = context,
                checkpointProgress = checkpointProgress,
                recoveryStore = recoveryStore,
                fatalExitPrompt = fatalExitPrompt,
                workflow = ::onWorkflow,
            )

        override fun onIntent(intent: InterviewIntent) {
            when (intent) {
                is InterviewIntent.Preparation -> preparation.handle(intent)
                is InterviewIntent.Timing -> timing.handle(intent)
                is InterviewIntent.Turn -> turn.handle(intent)
                is InterviewIntent.Finalization -> finalization.handle(intent)
                is InterviewIntent.Recovery -> recovery.handle(intent)
            }
        }

        private fun onWorkflow(event: InterviewWorkflow) {
            when (event) {
                is InterviewWorkflow.Timing -> {
                    onTiming(event)
                }

                is InterviewWorkflow.Turn -> {
                    onTurn(event)
                }

                is InterviewWorkflow.Submission -> {
                    onSubmission(event)
                }

                is InterviewWorkflow.Recovery -> {
                    onRecovery(event)
                }

                is InterviewWorkflow.Finalization -> {
                    onFinalization(event)
                }

                is InterviewWorkflow.ClearBeforeFirstQuestion -> {
                    preparation.clearBeforeFirstQuestion(event.completionEffect)
                }

                is InterviewWorkflow.GlobalError -> {
                    context.launch { GlobalErrorHandler.emit(event.event) }
                }
            }
        }

        private fun onTiming(event: InterviewWorkflow.Timing) {
            when (event) {
                InterviewWorkflow.CheckRestoredHardCap -> timing.reportRestoredHardCap()
                is InterviewWorkflow.RestoreTimer -> timing.restoreTimer(event.elapsedMillis)
                InterviewWorkflow.StartTicker -> timing.startTicker()
                InterviewWorkflow.StopTicker -> timing.stopTicker()
            }
        }

        private fun onTurn(event: InterviewWorkflow.Turn) {
            when (event) {
                is InterviewWorkflow.PlayCurrentQuestion -> {
                    turn.playCurrentQuestion(event.resetRetry)
                }

                is InterviewWorkflow.FinishAnswer -> {
                    turn.finishAnswer(event.requireSpeech)
                }

                InterviewWorkflow.ResetTimeline -> {
                    turn.resetTimeline()
                }

                InterviewWorkflow.StartQuestionSegmentAndPlayback -> {
                    turn.startQuestionSegmentAndPlayback()
                }
            }
        }

        private fun onSubmission(event: InterviewWorkflow.Submission) {
            when (event) {
                is InterviewWorkflow.SubmitAnswer -> {
                    submission.submit(event.command)
                }

                is InterviewWorkflow.CreateEndSubmission -> {
                    val command = turn.createSubmitCommand(null, event.request)
                    submission.submit(command)
                }

                is InterviewWorkflow.RequestEnd -> {
                    submission.requestEnd(event.request)
                }

                InterviewWorkflow.ClearSubmissionBusyState -> {
                    submission.clearSubmissionBusyState()
                }
            }
        }

        private fun onRecovery(event: InterviewWorkflow.Recovery) {
            when (event) {
                InterviewWorkflow.ConsumeRecoveryResult -> recovery.consumeRecoveryResult()
                InterviewWorkflow.TryApplyPendingRecovery -> recovery.tryApplyPendingRecovery()
                InterviewWorkflow.FatalExit -> recovery.beginFatalExit()
            }
        }

        private fun onFinalization(event: InterviewWorkflow.Finalization) {
            when (event) {
                is InterviewWorkflow.BeginFinishing -> {
                    finalization.beginFinishing(event.payload, event.reportGenerating)
                }

                is InterviewWorkflow.CompletionReasonChanged -> {
                    finalization.updateCompletionReason(event.reason)
                }

                InterviewWorkflow.WrapUpSegmentFinalized -> {
                    finalization.onWrapUpSegmentFinalized()
                }
            }
        }
    }
