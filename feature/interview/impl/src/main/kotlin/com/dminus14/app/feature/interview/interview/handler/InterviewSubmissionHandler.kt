package com.dminus14.app.feature.interview.interview.handler

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.AiTemporarilyUnavailableException
import com.dminus14.app.domain.exception.AnswerAlreadySubmittedException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.InterviewAnswerEndRequest
import com.dminus14.app.domain.model.InterviewEndType
import com.dminus14.app.domain.model.SubmitAnswerResult
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand
import com.dminus14.app.domain.usecase.DeleteInterviewMediaFileUseCase
import com.dminus14.app.domain.usecase.SavePendingInterviewAnswerUseCase
import com.dminus14.app.domain.usecase.SubmitAnswerUseCase
import com.dminus14.app.feature.interview.api.InterviewErrorType
import com.dminus14.app.feature.interview.interview.InterviewEffect
import com.dminus14.app.feature.interview.interview.InterviewMutation
import com.dminus14.app.feature.interview.interview.InterviewScreenState
import com.dminus14.app.feature.interview.interview.InterviewState
import com.dminus14.app.feature.interview.interview.InterviewTurnStateMachine
import com.dminus14.app.feature.interview.interview.InterviewWorkflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 같은 답변 명령의 보존·제출·재시도와 종료 요청의 직렬 실행을 담당한다. */
@Suppress("LongParameterList")
internal class InterviewSubmissionHandler(
    private val context: MviContext<InterviewState, InterviewMutation, InterviewEffect>,
    private val submitAnswer: SubmitAnswerUseCase,
    private val savePendingAnswer: SavePendingInterviewAnswerUseCase,
    private val deleteMediaFile: DeleteInterviewMediaFileUseCase,
    private val turnStateMachine: InterviewTurnStateMachine,
    private val workflow: (InterviewWorkflow) -> Unit,
) {
    private val state get() = context.state
    val pendingEndRequest get() = turnStateMachine.pendingEndRequest

    fun submit(command: SubmitInterviewAnswerCommand) {
        if (!turnStateMachine.beginSubmission()) return
        context.launch {
            savePendingAnswer(command).onFailure {
                currentCoroutineContext().ensureActive()
                if (it is CancellationException) throw it
                workflow(InterviewWorkflow.FatalExit)
                return@launch
            }
            currentCoroutineContext().ensureActive()
            submitAnswer(command)
                .onSuccess { result ->
                    currentCoroutineContext().ensureActive()
                    turnStateMachine.finishSubmission()
                    command.audioFile?.let { deleteMediaFile(it) }
                    savePendingAnswer(null)
                    currentCoroutineContext().ensureActive()
                    handleSubmitResult(command, result)
                }.onFailure { error -> handleSubmitFailure(command, error) }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun handleSubmitFailure(
        command: SubmitInterviewAnswerCommand,
        error: Throwable,
    ) {
        currentCoroutineContext().ensureActive()
        if (error is CancellationException) throw error
        when (error) {
            is AiTemporarilyUnavailableException -> {
                when (turnStateMachine.recordTemporaryFailure()) {
                    InterviewTurnStateMachine.TemporaryFailureAction.RETRY_AUTOMATICALLY -> {
                        turnStateMachine.prepareAutomaticRetry()
                        submit(command)
                    }

                    InterviewTurnStateMachine.TemporaryFailureAction.REQUIRE_USER_ACTION -> {
                        val saved = savePendingAnswer(command)
                        currentCoroutineContext().ensureActive()
                        if (saved.isFailure) {
                            workflow(InterviewWorkflow.FatalExit)
                            return
                        }
                        context.dispatch(InterviewMutation.AnswerSubmissionReleased)
                        context.sendEffect(
                            InterviewEffect.NavigateToError(
                                InterviewErrorType.SERVER_TEMPORARY,
                            ),
                        )
                    }
                }
            }

            is AnswerAlreadySubmittedException,
            is NetworkUnavailableException,
            -> {
                val saved = savePendingAnswer(command)
                currentCoroutineContext().ensureActive()
                if (saved.isFailure) {
                    workflow(InterviewWorkflow.FatalExit)
                    return
                }
                turnStateMachine.abortSubmission()
                context.dispatch(InterviewMutation.AnswerSubmissionReleased)
                context.sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.NETWORK))
            }

            is ServerException -> {
                clearSubmissionBusyState()
                workflow(InterviewWorkflow.GlobalError(GlobalAppEvent.ShowServerErrorAndExit))
            }

            else -> {
                workflow(InterviewWorkflow.FatalExit)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun handleSubmitResult(
        command: SubmitInterviewAnswerCommand,
        result: SubmitAnswerResult,
    ) {
        if (result.sessionEnded) {
            when (result.endType) {
                InterviewEndType.SttReset -> {
                    turnStateMachine.completeTerminal()
                    context.dispatch(InterviewMutation.AnswerSubmissionReleased)
                    context.sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.STT))
                }

                is InterviewEndType.Unknown -> {
                    workflow(InterviewWorkflow.FatalExit)
                }

                else -> {
                    turnStateMachine.completeTerminal()
                    workflow(
                        InterviewWorkflow.BeginFinishing(
                            result.wrapUpMessage?.ttsAudio,
                            result.reportGenerating,
                        ),
                    )
                }
            }
            return
        }

        if (command.endType != null) {
            workflow(InterviewWorkflow.FatalExit)
            return
        }

        val nextQuestion = result.nextQuestion
        if (nextQuestion == null) {
            workflow(InterviewWorkflow.FatalExit)
            return
        }
        context.dispatch(InterviewMutation.NextQuestionReceived(nextQuestion.questionId))
        workflow(InterviewWorkflow.ResetTimeline)
        val pendingEnd = turnStateMachine.pendingEndRequest
        if (pendingEnd != null) {
            submitEndWithoutAudio(pendingEnd)
        } else {
            workflow(InterviewWorkflow.StartQuestionSegmentAndPlayback)
        }
    }

    @Suppress("ReturnCount")
    fun requestEnd(request: InterviewAnswerEndRequest) {
        val current = state.value
        if (current.screenState == InterviewScreenState.FINISHING) return
        if (!turnStateMachine.requestEnd(request)) return
        if (request == InterviewAnswerEndRequest.HardCap) {
            context.dispatch(InterviewMutation.EndDialogsClosed)
        }
        if (current.isRequestInFlight || turnStateMachine.isSubmitting) {
            return
        }
        when (current.screenState) {
            InterviewScreenState.ANSWER_RECORDING -> {
                workflow(InterviewWorkflow.FinishAnswer(false))
            }

            InterviewScreenState.QUESTION_PLAYING -> {
                context.sendEffect(InterviewEffect.StopRecordingSegment)
                submitEndWithoutAudio(request)
            }

            InterviewScreenState.DEVICE_CHECK,
            InterviewScreenState.QUESTION_PREPARING,
            InterviewScreenState.START_GUIDE,
            -> {
                workflow(InterviewWorkflow.ClearBeforeFirstQuestion(null))
            }

            InterviewScreenState.ANSWER_SUBMITTING -> {
                Unit
            }

            InterviewScreenState.FINISHING -> {
                Unit
            }
        }
    }

    private fun submitEndWithoutAudio(request: InterviewAnswerEndRequest) {
        context.dispatch(InterviewMutation.AnswerSubmissionStarted)
        workflow(InterviewWorkflow.CreateEndSubmission(request))
    }

    fun clearSubmissionBusyState() {
        turnStateMachine.abortSubmission()
        context.dispatch(InterviewMutation.AnswerSubmissionReleased)
    }
}
