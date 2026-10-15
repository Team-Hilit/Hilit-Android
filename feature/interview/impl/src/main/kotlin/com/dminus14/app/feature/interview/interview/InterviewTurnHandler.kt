package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.model.InterviewAnswerEndRequest
import com.dminus14.app.domain.model.InterviewMediaFinalizeState
import com.dminus14.app.domain.model.InterviewMediaSegment
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand
import com.dminus14.app.domain.usecase.CreateInterviewMediaSegmentUseCase
import com.dminus14.app.domain.usecase.FinalizeInterviewMediaSegmentUseCase
import com.dminus14.app.domain.usecase.GetInterviewMediaManifestUseCase
import com.dminus14.app.domain.usecase.GetQuestionAudioStreamUrlUseCase
import com.dminus14.app.feature.interview.api.InterviewErrorType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 질문 재생, 녹화 타임라인과 중복 없는 답변 제출을 관리한다. */
@Suppress("LongParameterList", "TooManyFunctions")
internal class InterviewTurnHandler(
    private val context: MviContext<InterviewState, InterviewMutation, InterviewEffect>,
    private val getQuestionAudioUrl: GetQuestionAudioStreamUrlUseCase,
    private val finalizeMediaSegment: FinalizeInterviewMediaSegmentUseCase,
    private val getMediaManifest: GetInterviewMediaManifestUseCase,
    private val createMediaSegment: CreateInterviewMediaSegmentUseCase,
    private val workflow: (InterviewWorkflow) -> Unit,
    private val isConnectionInterrupted: () -> Boolean,
    private val pendingEndRequest: () -> InterviewAnswerEndRequest?,
) {
    private val state get() = context.state
    private var questionPlaybackRetryCount = 0
    private var activeQuestionUrl: String? = null
    private var questionAudioStartedAtMillis: Long? = null
    private var questionAudioEndedAtMillis: Long? = null
    private var answerStartedAtMillis: Long? = null
    private var answerEndedAtMillis: Long? = null

    @Suppress("CyclomaticComplexMethod")
    fun handle(intent: InterviewIntent.Turn) {
        when (intent) {
            InterviewIntent.Turn.ReportQuestionPlaybackStarted -> {
                questionAudioStartedAtMillis = state.value.elapsedMillis
            }

            InterviewIntent.Turn.ReportQuestionPlaybackCompleted -> {
                onQuestionPlaybackCompleted()
            }

            InterviewIntent.Turn.ReportQuestionPlaybackFailure -> {
                onQuestionPlaybackFailure()
            }

            InterviewIntent.Turn.ClickRetryQuestionAudio -> {
                playCurrentQuestion(resetRetry = true)
            }

            InterviewIntent.Turn.ReportAnswerSpeechStarted -> {
                context.dispatch(InterviewMutation.SpeechStarted)
            }

            InterviewIntent.Turn.ReportAnswerSilenceElapsed -> {
                finishAnswer(requireSpeech = true)
            }

            InterviewIntent.Turn.ClickFinishAnswer -> {
                finishAnswer(requireSpeech = true)
            }

            is InterviewIntent.Turn.ReportRecordingSegmentFinalized -> {
                onRecordingSegmentFinalized(intent.segment)
            }

            is InterviewIntent.Turn.ReportAnswerRecordingCompleted -> {
                onAnswerAudioCompleted(intent.audioSegment)
            }

            InterviewIntent.Turn.ReportAnswerAudioMergeFailure -> {
                context.sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.MIC_DEVICE))
            }

            InterviewIntent.Turn.ClickFinishInterview -> {
                if (canOpenEndModal()) {
                    context.dispatch(InterviewMutation.FinishConfirmationChanged(true))
                }
            }

            InterviewIntent.Turn.ConfirmFinishInterview -> {
                context.dispatch(InterviewMutation.FinishConfirmationChanged(false))
                workflow(InterviewWorkflow.RequestEnd(InterviewAnswerEndRequest.ManualEnd))
            }

            InterviewIntent.Turn.DismissFinishInterview -> {
                context.dispatch(InterviewMutation.FinishConfirmationChanged(false))
            }

            InterviewIntent.Turn.ClickExitInterview -> {
                if (canOpenEndModal()) {
                    context.dispatch(InterviewMutation.EarlyExitWarningChanged(true))
                }
            }

            InterviewIntent.Turn.ConfirmEarlyExit -> {
                context.dispatch(InterviewMutation.EarlyExitWarningChanged(false))
                workflow(InterviewWorkflow.RequestEnd(InterviewAnswerEndRequest.BackExit))
            }

            InterviewIntent.Turn.DismissEarlyExit -> {
                context.dispatch(InterviewMutation.EarlyExitWarningChanged(false))
            }
        }
    }

    fun playCurrentQuestion(resetRetry: Boolean) {
        val sessionId = state.value.sessionId ?: return
        val questionId = state.value.questionId ?: return
        if (resetRetry) questionPlaybackRetryCount = 0
        val url = getQuestionAudioUrl(sessionId, questionId)
        activeQuestionUrl = url
        context.dispatch(InterviewMutation.QuestionPlaybackStarted)
        context.sendEffect(InterviewEffect.PlayQuestionAudio(url))
    }

    private fun onQuestionPlaybackFailure() {
        if (questionPlaybackRetryCount == 0) {
            questionPlaybackRetryCount = 1
            activeQuestionUrl?.let { context.sendEffect(InterviewEffect.PlayQuestionAudio(it)) }
        } else {
            context.dispatch(InterviewMutation.QuestionPlaybackRetryRequired)
        }
    }

    private fun onQuestionPlaybackCompleted() {
        if (state.value.screenState != InterviewScreenState.QUESTION_PLAYING) return
        questionAudioEndedAtMillis = state.value.elapsedMillis
        context.sendEffect(InterviewEffect.StopRecordingSegment)
    }

    private fun onRecordingSegmentFinalized(segment: InterviewMediaSegment) {
        context.launch {
            val endedAt = state.value.elapsedMillis
            val sessionId = state.value.sessionId ?: return@launch
            finalizeMediaSegment(sessionId, segment.sequence, endedAt)
            currentCoroutineContext().ensureActive()
            if (isConnectionInterrupted()) return@launch
            when {
                segment.type == InterviewMediaSegmentType.QUESTION_VIDEO &&
                    state.value.screenState == InterviewScreenState.FINISHING -> {
                    workflow(InterviewWorkflow.WrapUpSegmentFinalized)
                }

                segment.type == InterviewMediaSegmentType.QUESTION_VIDEO &&
                    state.value.screenState == InterviewScreenState.QUESTION_PLAYING -> {
                    val sessionId = state.value.sessionId ?: return@launch
                    val questionId = state.value.questionId ?: return@launch
                    answerStartedAtMillis = endedAt
                    context.dispatch(InterviewMutation.AnswerRecordingStarted)
                    context.sendEffect(
                        InterviewEffect.StartRecordingSegment(
                            sessionId = sessionId,
                            type = InterviewMediaSegmentType.ANSWER_VIDEO,
                            questionId = questionId,
                            startedAtMillis = endedAt,
                        ),
                    )
                }

                segment.type == InterviewMediaSegmentType.ANSWER_VIDEO &&
                    state.value.screenState == InterviewScreenState.ANSWER_SUBMITTING -> {
                    createAndExportAnswerAudio()
                }
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun createAndExportAnswerAudio() {
        val sessionId = state.value.sessionId ?: return
        val questionId = state.value.questionId ?: return
        val inputRefs =
            getMediaManifest(sessionId)
                ?.segments
                .orEmpty()
                .filter {
                    it.type == InterviewMediaSegmentType.ANSWER_VIDEO &&
                        it.questionId == questionId &&
                        it.finalizeState == InterviewMediaFinalizeState.FINALIZED
                }.sortedBy { it.sequence }
                .map { it.mediaRef }
        if (inputRefs.isEmpty()) {
            context.sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.MIC_DEVICE))
            return
        }
        val audioSegment =
            createMediaSegment(
                sessionId = sessionId,
                type = InterviewMediaSegmentType.ANSWER_AUDIO,
                questionId = questionId,
                startedAtMillis = answerStartedAtMillis ?: state.value.elapsedMillis,
                gapBeforeMillis = 0L,
                extension = "m4a",
            )
        context.sendEffect(
            InterviewEffect.ExportAnswerAudio(
                inputRefs = inputRefs,
                outputSegment = audioSegment,
            ),
        )
    }

    fun finishAnswer(requireSpeech: Boolean) {
        val current = state.value
        if (current.screenState != InterviewScreenState.ANSWER_RECORDING) return
        if (requireSpeech && !current.hasSpeechStarted) return
        answerEndedAtMillis = current.elapsedMillis
        context.dispatch(InterviewMutation.AnswerSubmissionStarted)
        context.sendEffect(InterviewEffect.StopRecordingSegment)
    }

    private fun onAnswerAudioCompleted(audioSegment: InterviewMediaSegment) {
        context.launch {
            val sessionId = state.value.sessionId ?: return@launch
            finalizeMediaSegment(sessionId, audioSegment.sequence, state.value.elapsedMillis)
            currentCoroutineContext().ensureActive()
            val command =
                createSubmitCommand(
                    audioSegment.mediaRef,
                    pendingEndRequest(),
                )
            workflow(InterviewWorkflow.SubmitAnswer(command))
        }
    }

    fun createSubmitCommand(
        audioRef: com.dminus14.app.domain.model.InterviewMediaFileRef?,
        endRequest: InterviewAnswerEndRequest?,
    ): SubmitInterviewAnswerCommand {
        val start = answerStartedAtMillis
        val end = answerEndedAtMillis ?: state.value.elapsedMillis
        return SubmitInterviewAnswerCommand(
            sessionId = requireNotNull(state.value.sessionId),
            questionId = requireNotNull(state.value.questionId),
            isWrapUp = state.value.isWrapUp,
            questionAudioStartAt = questionAudioStartedAtMillis?.seconds,
            questionAudioEndAt = questionAudioEndedAtMillis?.seconds,
            answerStartAt = start?.seconds,
            answerEndAt = end.seconds,
            answerDuration = start?.let { (end - it).coerceAtLeast(0L).seconds },
            endType = endRequest,
            audioFile = audioRef,
        )
    }

    fun startQuestionSegmentAndPlayback() {
        val current = state.value
        val sessionId = current.sessionId ?: return
        val questionId = current.questionId ?: return
        context.dispatch(InterviewMutation.QuestionRecordingStarted)
        context.sendEffect(
            InterviewEffect.StartRecordingSegment(
                sessionId = sessionId,
                type = InterviewMediaSegmentType.QUESTION_VIDEO,
                questionId = questionId,
                startedAtMillis = current.elapsedMillis,
            ),
        )
        playCurrentQuestion(resetRetry = true)
    }

    private fun canOpenEndModal(): Boolean =
        pendingEndRequest() == null &&
            state.value.screenState != InterviewScreenState.FINISHING

    fun resetTimeline() {
        questionAudioStartedAtMillis = null
        questionAudioEndedAtMillis = null
        answerStartedAtMillis = null
        answerEndedAtMillis = null
    }

    private val Long.seconds: Float
        get() = this / 1_000f
}
