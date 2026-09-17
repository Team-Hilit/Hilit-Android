package com.dminus14.app.feature.interview.interview

import androidx.lifecycle.viewModelScope
import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.modal.GlobalModalResult
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.exception.AiTemporarilyUnavailableException
import com.dminus14.app.domain.exception.AnswerAlreadySubmittedException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.InterviewAnswerEndRequest
import com.dminus14.app.domain.model.InterviewEndType
import com.dminus14.app.domain.model.InterviewMediaFinalizeState
import com.dminus14.app.domain.model.InterviewMediaSegment
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewSessionStatusType
import com.dminus14.app.domain.model.InterviewUploadNetworkPolicy
import com.dminus14.app.domain.model.SubmitAnswerResult
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand
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
import com.dminus14.app.feature.interview.InterviewConstants
import com.dminus14.app.feature.interview.api.InterviewErrorType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@Suppress(
    "LargeClass",
    "LongMethod",
    "LongParameterList",
    "ReturnCount",
    "TooManyFunctions",
)
class InterviewViewModel
    @Inject
    constructor(
        private val cleanupExpiredData: CleanupExpiredInterviewDataUseCase,
        private val getProgress: GetInterviewProgressUseCase,
        private val getSession: GetInterviewSessionUseCase,
        private val startTimer: StartInterviewTimerUseCase,
        private val getElapsedTime: GetInterviewElapsedTimeUseCase,
        private val getMediaManifest: GetInterviewMediaManifestUseCase,
        private val checkpointProgress: CheckpointInterviewProgressUseCase,
        private val getQuestionAudioUrl: GetQuestionAudioStreamUrlUseCase,
        private val createMediaSegment: CreateInterviewMediaSegmentUseCase,
        private val finalizeMediaSegment: FinalizeInterviewMediaSegmentUseCase,
        private val submitAnswer: SubmitAnswerUseCase,
        private val deleteMediaFile: DeleteInterviewMediaFileUseCase,
        private val savePendingAnswer: SavePendingInterviewAnswerUseCase,
        private val saveWrapUpRange: SaveInterviewWrapUpRangeUseCase,
        private val deleteSession: DeleteInterviewSessionUseCase,
        private val recoveryStore: InterviewRecoveryStore,
        private val timerCoordinator: InterviewTimerCoordinator,
        private val turnStateMachine: InterviewTurnStateMachine,
        private val fatalExitPrompt: InterviewFatalExitPrompt,
    ) : MviViewModel<InterviewIntent, InterviewState, InterviewEffect>(InterviewState()) {
        private var preparationJob: Job? = null
        private var preparationStageJob: Job? = null
        private var timerJob: Job? = null
        private var hasLoaded = false
        private var isDeviceCheckMinDurationElapsed = false
        private var isQuestionPreparingMinDurationElapsed = false
        private var questionPlaybackRetryCount = 0
        private var activeQuestionUrl: String? = null
        private var questionAudioStartedAtMillis: Long? = null
        private var questionAudioEndedAtMillis: Long? = null
        private var answerStartedAtMillis: Long? = null
        private var answerEndedAtMillis: Long? = null
        private var connectionInterrupted = false
        private var wasBackgrounded = false
        private var pendingRecoveryResult: InterviewRecoveryStore.Result? = null
        private var completionReason = InterviewCompletionReason.COMPLETED
        private var wrapUpStartedAtMillis: Long? = null
        private var isWrapUpRecording = false
        private var finalizationWatchdogJob: Job? = null
        private var fatalExitJob: Job? = null
        private var wrapUpPayload: String? = null
        private var lastUploadNetworkPolicy: InterviewUploadNetworkPolicy? = null
        private var isFinalOutcomeEmitted = false

        @Suppress("CyclomaticComplexMethod")
        override fun onIntent(intent: InterviewIntent) {
            when (intent) {
                InterviewIntent.LoadInterview -> {
                    loadInterview()
                }

                InterviewIntent.CheckCameraPermission,
                InterviewIntent.ClickRetryDeviceCheck,
                -> {
                    sendEffect(InterviewEffect.RequestCameraPermission)
                }

                InterviewIntent.ClickOpenSettings -> {
                    state.value.permanentlyDeniedPermission?.let {
                        sendEffect(InterviewEffect.OpenAppSettings(it))
                    }
                }

                InterviewIntent.ReportCameraPermissionGranted -> {
                    onCameraPermissionGranted()
                }

                InterviewIntent.ReportCameraReady -> {
                    onCameraReady()
                }

                is InterviewIntent.ReportCameraPermissionDenied -> {
                    reduce {
                        copy(
                            isCameraPermissionGranted = false,
                            isCameraReady = false,
                            permanentlyDeniedPermission =
                                InterviewPermission.CAMERA.takeIf { intent.permanentlyDenied },
                        )
                    }
                }

                InterviewIntent.ReportCameraBindingFailure -> {
                    sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.MIC_DEVICE))
                }

                InterviewIntent.ReportMicrophoneReady -> {
                    onMicrophoneReady()
                }

                is InterviewIntent.ReportMicrophonePermissionDenied -> {
                    reduce {
                        copy(
                            isMicrophoneReady = false,
                            permanentlyDeniedPermission =
                                InterviewPermission.MICROPHONE.takeIf { intent.permanentlyDenied },
                        )
                    }
                }

                InterviewIntent.ReportMicrophoneFailure -> {
                    sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.MIC_DEVICE))
                }

                is InterviewIntent.ReportStorageAvailability -> {
                    onStorageAvailable(intent.availableBytes)
                }

                InterviewIntent.ClickPermissionDeniedBack -> {
                    clearBeforeFirstQuestion(InterviewEffect.PermissionDeniedExitRequested)
                }

                InterviewIntent.StartInterview -> {
                    startInterview()
                }

                InterviewIntent.ReportAppBackgrounded -> {
                    onBackgrounded()
                }

                InterviewIntent.ReportAppForegrounded -> {
                    onForegrounded()
                }

                is InterviewIntent.UpdateElapsedTime -> {
                    updateElapsedTime(intent.elapsedMillis)
                }

                InterviewIntent.ReportHardCapReached -> {
                    requestEnd(InterviewAnswerEndRequest.HardCap)
                }

                InterviewIntent.ReportQuestionPlaybackStarted -> {
                    questionAudioStartedAtMillis = state.value.elapsedMillis
                }

                InterviewIntent.ReportQuestionPlaybackCompleted -> {
                    onQuestionPlaybackCompleted()
                }

                InterviewIntent.ReportQuestionPlaybackFailure -> {
                    onQuestionPlaybackFailure()
                }

                InterviewIntent.ClickRetryQuestionAudio -> {
                    playCurrentQuestion(resetRetry = true)
                }

                InterviewIntent.ReportAnswerSpeechStarted -> {
                    reduce { copy(hasSpeechStarted = true) }
                }

                InterviewIntent.ReportAnswerSilenceElapsed -> {
                    finishAnswer(requireSpeech = true)
                }

                InterviewIntent.ClickFinishAnswer -> {
                    finishAnswer(requireSpeech = true)
                }

                is InterviewIntent.ReportRecordingSegmentFinalized -> {
                    onRecordingSegmentFinalized(intent.segment)
                }

                is InterviewIntent.ReportAnswerRecordingCompleted -> {
                    onAnswerAudioCompleted(intent.audioSegment)
                }

                InterviewIntent.ReportAnswerAudioMergeFailure -> {
                    sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.MIC_DEVICE))
                }

                InterviewIntent.ReportRecordingFailure -> {
                    onRecordingFailure()
                }

                InterviewIntent.ClickFinishInterview -> {
                    if (canOpenEndModal()) {
                        reduce { copy(showFinishConfirmation = true) }
                    }
                }

                InterviewIntent.ConfirmFinishInterview -> {
                    reduce { copy(showFinishConfirmation = false) }
                    requestEnd(InterviewAnswerEndRequest.ManualEnd)
                }

                InterviewIntent.DismissFinishInterview -> {
                    reduce { copy(showFinishConfirmation = false) }
                }

                InterviewIntent.ClickExitInterview -> {
                    if (canOpenEndModal()) {
                        reduce { copy(showEarlyExitWarning = true) }
                    }
                }

                InterviewIntent.ConfirmEarlyExit -> {
                    reduce { copy(showEarlyExitWarning = false) }
                    requestEnd(InterviewAnswerEndRequest.BackExit)
                }

                InterviewIntent.DismissEarlyExit -> {
                    reduce { copy(showEarlyExitWarning = false) }
                }

                InterviewIntent.ConfirmMeteredUpload -> {
                    reduce { copy(showMeteredUploadConfirmation = false) }
                    enqueueUpload(InterviewUploadNetworkPolicy.CONNECTED)
                }

                InterviewIntent.DismissMeteredUpload -> {
                    reduce { copy(showMeteredUploadConfirmation = false) }
                    enqueueUpload(InterviewUploadNetworkPolicy.UNMETERED)
                }

                is InterviewIntent.ReportUploadNotificationPermission -> {
                    if (!intent.isGranted) {
                        sendEffect(InterviewEffect.ShowUploadNotificationPermissionDenied)
                    }
                    sendEffect(InterviewEffect.CheckUploadNetwork)
                }

                is InterviewIntent.ReportUploadNetworkMetered -> {
                    if (intent.isMetered) {
                        reduce { copy(showMeteredUploadConfirmation = true) }
                    } else {
                        enqueueUpload(InterviewUploadNetworkPolicy.UNMETERED)
                    }
                }

                InterviewIntent.ReportVideoUploadEnqueued -> {
                    finishUploadHandoff()
                }

                InterviewIntent.ReportVideoUploadEnqueueFailure -> {
                    if (!isFinalOutcomeEmitted) {
                        reduce {
                            copy(
                                isUploadHandoffInProgress = false,
                                finalizationFailure =
                                    InterviewFinalizationFailure.UPLOAD_HANDOFF_FAILURE,
                            )
                        }
                    }
                }

                InterviewIntent.ClickRetryFinalization -> {
                    retryFinalization()
                }

                InterviewIntent.ClickExitFinalization -> {
                    exitFinalization()
                }

                InterviewIntent.ReportWrapUpPlaybackCompleted,
                InterviewIntent.ReportWrapUpPlaybackFailure,
                -> {
                    finishWrapUpPlayback()
                }

                InterviewIntent.ReportNetworkDisconnected -> {
                    onNetworkDisconnected()
                }

                InterviewIntent.ReportNetworkRestored -> {
                    Unit
                }

                InterviewIntent.ConsumeRecoveryResult -> {
                    consumeRecoveryResult()
                }
            }
        }

        private fun loadInterview() {
            if (hasLoaded) {
                consumeRecoveryResult()
                return
            }
            hasLoaded = true
            startDeviceCheckMinimumDuration()
            viewModelScope.launch {
                cleanupExpiredData()
                val progress = getProgress()
                if (progress == null) {
                    sendEffect(InterviewEffect.PrerequisiteMissing)
                    return@launch
                }
                reduce { copy(sessionId = progress.sessionId) }
                if (progress.timerStartedAtEpochMillis != null) {
                    val elapsedMillis =
                        getElapsedTime().coerceIn(
                            0L,
                            InterviewConstants.MAX_INTERVIEW_SECONDS * MILLIS_PER_SECOND,
                        )
                    val hardCapReached = timerCoordinator.restore(elapsedMillis)
                    reduce { copy(elapsedMillis = elapsedMillis) }
                    startTicker()
                    sendEffect(InterviewEffect.RequestCameraPermission)
                    sendEffect(InterviewEffect.CheckStorageAvailability)
                    sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.NETWORK))
                    if (hardCapReached) onIntent(InterviewIntent.ReportHardCapReached)
                    return@launch
                }
                sendEffect(InterviewEffect.RequestCameraPermission)
                sendEffect(InterviewEffect.CheckStorageAvailability)
                startPreparationPolling(progress.sessionId)
            }
        }

        private fun startPreparationPolling(sessionId: Long) {
            preparationJob?.cancel()
            preparationJob =
                viewModelScope.launch {
                    while (isActive) {
                        val sessionResult = getSession(sessionId)
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
                                    reduce {
                                        copy(
                                            questionId = questionId,
                                            isServerReady = true,
                                        )
                                    }
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
                                emitGlobalError(GlobalAppEvent.ShowUnknownError)
                                return@launch
                            }
                        }
                        delay(InterviewConstants.SESSION_POLL_INTERVAL_MILLIS)
                    }
                }
        }

        private fun onCameraPermissionGranted() {
            reduce {
                copy(
                    isCameraPermissionGranted = true,
                    permanentlyDeniedPermission = null,
                )
            }
            sendEffect(InterviewEffect.RequestMicrophonePermission)
        }

        private fun onCameraReady() {
            reduce { copy(isCameraPermissionGranted = true, isCameraReady = true) }
            updatePreparationGate()
            tryApplyPendingRecovery()
        }

        private fun onMicrophoneReady() {
            reduce { copy(isMicrophoneReady = true, permanentlyDeniedPermission = null) }
            updatePreparationGate()
            tryApplyPendingRecovery()
        }

        private fun onStorageAvailable(availableBytes: Long) {
            reduce {
                copy(
                    availableStorageBytes = availableBytes,
                    hasEnoughStorage = availableBytes >= InterviewConstants.REQUIRED_STORAGE_BYTES,
                )
            }
            updatePreparationGate()
            tryApplyPendingRecovery()
        }

        private fun updatePreparationGate() {
            val current = state.value
            if (!current.isReadyToStart) return

            when (current.screenState) {
                InterviewScreenState.DEVICE_CHECK -> {
                    if (isDeviceCheckMinDurationElapsed) {
                        reduce { copy(screenState = InterviewScreenState.QUESTION_PREPARING) }
                        startQuestionPreparingMinimumDuration()
                    }
                }

                InterviewScreenState.QUESTION_PREPARING -> {
                    if (isQuestionPreparingMinDurationElapsed) {
                        reduce { copy(screenState = InterviewScreenState.START_GUIDE) }
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
                viewModelScope.launch {
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
                viewModelScope.launch {
                    delay(InterviewConstants.PREPARATION_STAGE_MIN_DURATION_MILLIS)
                    isQuestionPreparingMinDurationElapsed = true
                    preparationStageJob = null
                    updatePreparationGate()
                }
        }

        private fun startInterview() {
            val current = state.value
            if (current.screenState != InterviewScreenState.START_GUIDE ||
                !current.isReadyToStart
            ) {
                return
            }
            val sessionId = current.sessionId ?: return
            val questionId = current.questionId ?: return
            viewModelScope.launch {
                startTimer()
                reduce {
                    copy(
                        screenState = InterviewScreenState.QUESTION_PLAYING,
                        isRequestInFlight = false,
                    )
                }
                sendEffect(
                    InterviewEffect.StartRecordingSegment(
                        sessionId = sessionId,
                        type = InterviewMediaSegmentType.QUESTION_VIDEO,
                        questionId = questionId,
                        startedAtMillis = state.value.elapsedMillis,
                    ),
                )
                playCurrentQuestion(resetRetry = true)
                startTicker()
            }
        }

        private fun startTicker() {
            if (timerJob?.isActive == true) return
            timerJob =
                viewModelScope.launch {
                    while (isActive) {
                        onIntent(InterviewIntent.UpdateElapsedTime(getElapsedTime()))
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
            reduce { copy(elapsedMillis = bounded) }
            timerCoordinator.update(bounded).forEach { event ->
                when (event) {
                    InterviewTimerCoordinator.Event.EARLY_FINISH -> {
                        sendEffect(InterviewEffect.ShowEarlyFinishAvailable)
                    }

                    InterviewTimerCoordinator.Event.WRAP_UP -> {
                        Unit
                    }

                    InterviewTimerCoordinator.Event.COUNTDOWN -> {
                        sendEffect(InterviewEffect.PlayFinalCountdown)
                    }

                    InterviewTimerCoordinator.Event.HARD_CAP -> {
                        onIntent(InterviewIntent.ReportHardCapReached)
                    }
                }
            }
        }

        private fun playCurrentQuestion(resetRetry: Boolean) {
            val sessionId = state.value.sessionId ?: return
            val questionId = state.value.questionId ?: return
            if (resetRetry) questionPlaybackRetryCount = 0
            val url = getQuestionAudioUrl(sessionId, questionId)
            activeQuestionUrl = url
            reduce {
                copy(
                    screenState = InterviewScreenState.QUESTION_PLAYING,
                    isQuestionAudioRetryVisible = false,
                    hasSpeechStarted = false,
                )
            }
            sendEffect(InterviewEffect.PlayQuestionAudio(url))
        }

        private fun onQuestionPlaybackFailure() {
            if (questionPlaybackRetryCount == 0) {
                questionPlaybackRetryCount = 1
                activeQuestionUrl?.let { sendEffect(InterviewEffect.PlayQuestionAudio(it)) }
            } else {
                reduce { copy(isQuestionAudioRetryVisible = true) }
            }
        }

        private fun onQuestionPlaybackCompleted() {
            if (state.value.screenState != InterviewScreenState.QUESTION_PLAYING) return
            questionAudioEndedAtMillis = state.value.elapsedMillis
            sendEffect(InterviewEffect.StopRecordingSegment)
        }

        private fun onRecordingSegmentFinalized(segment: InterviewMediaSegment) {
            viewModelScope.launch {
                val endedAt = state.value.elapsedMillis
                val sessionId = state.value.sessionId ?: return@launch
                finalizeMediaSegment(sessionId, segment.sequence, endedAt)
                if (connectionInterrupted) return@launch
                when {
                    segment.type == InterviewMediaSegmentType.QUESTION_VIDEO &&
                        state.value.screenState == InterviewScreenState.FINISHING &&
                        isWrapUpRecording -> {
                        isWrapUpRecording = false
                        finalizationWatchdogJob?.cancel()
                        finalizationWatchdogJob = null
                        reduce { copy(finalizationFailure = null) }
                        completeInterview()
                    }

                    segment.type == InterviewMediaSegmentType.QUESTION_VIDEO &&
                        state.value.screenState == InterviewScreenState.QUESTION_PLAYING -> {
                        val sessionId = state.value.sessionId ?: return@launch
                        val questionId = state.value.questionId ?: return@launch
                        answerStartedAtMillis = endedAt
                        reduce {
                            copy(
                                screenState = InterviewScreenState.ANSWER_RECORDING,
                                hasSpeechStarted = false,
                            )
                        }
                        sendEffect(
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
                sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.MIC_DEVICE))
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
            sendEffect(
                InterviewEffect.ExportAnswerAudio(
                    inputRefs = inputRefs,
                    outputSegment = audioSegment,
                ),
            )
        }

        private fun finishAnswer(requireSpeech: Boolean) {
            val current = state.value
            if (current.screenState != InterviewScreenState.ANSWER_RECORDING) return
            if (requireSpeech && !current.hasSpeechStarted) return
            answerEndedAtMillis = current.elapsedMillis
            reduce {
                copy(
                    screenState = InterviewScreenState.ANSWER_SUBMITTING,
                    isRequestInFlight = true,
                )
            }
            sendEffect(InterviewEffect.StopRecordingSegment)
        }

        private fun onAnswerAudioCompleted(audioSegment: InterviewMediaSegment) {
            viewModelScope.launch {
                val sessionId = state.value.sessionId ?: return@launch
                finalizeMediaSegment(sessionId, audioSegment.sequence, state.value.elapsedMillis)
                val command =
                    createSubmitCommand(
                        audioSegment.mediaRef,
                        turnStateMachine.pendingEndRequest,
                    )
                submit(command)
            }
        }

        private fun createSubmitCommand(
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

        private fun submit(command: SubmitInterviewAnswerCommand) {
            if (!turnStateMachine.beginSubmission()) return
            viewModelScope.launch {
                savePendingAnswer(command).onFailure {
                    beginFatalExit()
                    return@launch
                }
                submitAnswer(command)
                    .onSuccess { result ->
                        turnStateMachine.finishSubmission()
                        command.audioFile?.let { deleteMediaFile(it) }
                        savePendingAnswer(null)
                        handleSubmitResult(command, result)
                    }.onFailure { error -> handleSubmitFailure(command, error) }
            }
        }

        private suspend fun handleSubmitFailure(
            command: SubmitInterviewAnswerCommand,
            error: Throwable,
        ) {
            when (error) {
                is AiTemporarilyUnavailableException -> {
                    when (turnStateMachine.recordTemporaryFailure()) {
                        InterviewTurnStateMachine.TemporaryFailureAction.RETRY_AUTOMATICALLY -> {
                            turnStateMachine.prepareAutomaticRetry()
                            submit(command)
                        }

                        InterviewTurnStateMachine.TemporaryFailureAction.REQUIRE_USER_ACTION -> {
                            if (savePendingAnswer(command).isFailure) {
                                beginFatalExit()
                                return
                            }
                            reduce { copy(isRequestInFlight = false) }
                            sendEffect(
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
                    if (savePendingAnswer(command).isFailure) {
                        beginFatalExit()
                        return
                    }
                    turnStateMachine.abortSubmission()
                    reduce { copy(isRequestInFlight = false) }
                    sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.NETWORK))
                }

                is ServerException -> {
                    clearSubmissionBusyState()
                    emitGlobalError(GlobalAppEvent.ShowServerErrorAndExit)
                }

                else -> {
                    beginFatalExit()
                }
            }
        }

        private fun handleSubmitResult(
            command: SubmitInterviewAnswerCommand,
            result: SubmitAnswerResult,
        ) {
            if (result.sessionEnded) {
                when (result.endType) {
                    InterviewEndType.SttReset -> {
                        turnStateMachine.completeTerminal()
                        reduce { copy(isRequestInFlight = false) }
                        sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.STT))
                    }

                    is InterviewEndType.Unknown -> {
                        beginFatalExit()
                    }

                    else -> {
                        turnStateMachine.completeTerminal()
                        beginFinishing(result.wrapUpMessage?.ttsAudio, result.reportGenerating)
                    }
                }
                return
            }

            if (command.endType != null) {
                beginFatalExit()
                return
            }

            val nextQuestion = result.nextQuestion
            if (nextQuestion == null) {
                beginFatalExit()
                return
            }
            reduce {
                copy(
                    questionId = nextQuestion.questionId,
                    isRequestInFlight = false,
                )
            }
            resetTimeline()
            val pendingEnd = turnStateMachine.pendingEndRequest
            if (pendingEnd != null) {
                submitEndWithoutAudio(pendingEnd)
            } else {
                startQuestionSegmentAndPlayback()
            }
        }

        private fun startQuestionSegmentAndPlayback() {
            val current = state.value
            val sessionId = current.sessionId ?: return
            val questionId = current.questionId ?: return
            reduce {
                copy(
                    screenState = InterviewScreenState.QUESTION_PLAYING,
                    hasSpeechStarted = false,
                )
            }
            sendEffect(
                InterviewEffect.StartRecordingSegment(
                    sessionId = sessionId,
                    type = InterviewMediaSegmentType.QUESTION_VIDEO,
                    questionId = questionId,
                    startedAtMillis = current.elapsedMillis,
                ),
            )
            playCurrentQuestion(resetRetry = true)
        }

        private fun requestEnd(request: InterviewAnswerEndRequest) {
            val current = state.value
            if (current.screenState == InterviewScreenState.FINISHING) return
            if (!turnStateMachine.requestEnd(request)) return
            if (request == InterviewAnswerEndRequest.HardCap) {
                reduce {
                    copy(
                        showFinishConfirmation = false,
                        showEarlyExitWarning = false,
                    )
                }
            }
            if (current.isRequestInFlight || turnStateMachine.isSubmitting) {
                return
            }
            when (current.screenState) {
                InterviewScreenState.ANSWER_RECORDING -> {
                    finishAnswer(requireSpeech = false)
                }

                InterviewScreenState.QUESTION_PLAYING -> {
                    sendEffect(InterviewEffect.StopRecordingSegment)
                    submitEndWithoutAudio(request)
                }

                InterviewScreenState.DEVICE_CHECK,
                InterviewScreenState.QUESTION_PREPARING,
                InterviewScreenState.START_GUIDE,
                -> {
                    clearBeforeFirstQuestion()
                }

                InterviewScreenState.ANSWER_SUBMITTING -> {
                    Unit
                }

                InterviewScreenState.FINISHING -> {
                    Unit
                }
            }
        }

        /**
         * 첫 질문 시작 전에는 서버 정리 API가 없어 로컬 상태만 제거한다.
         * 서버의 보류 만료·환불 처리와 상태가 어긋날 수 있으므로 정리 API가 생기면 교체해야 한다.
         */
        private fun clearBeforeFirstQuestion(completionEffect: InterviewEffect? = null) {
            val sessionId = state.value.sessionId ?: return
            preparationJob?.cancel()
            preparationJob = null
            preparationStageJob?.cancel()
            preparationStageJob = null
            viewModelScope.launch {
                deleteSession(sessionId)
                sendEffect(
                    completionEffect
                        ?: InterviewEffect.InterviewEnded(
                            InterviewCompletionReason.ABANDONED,
                            sessionId,
                        ),
                )
            }
        }

        private fun submitEndWithoutAudio(request: InterviewAnswerEndRequest) {
            reduce {
                copy(
                    screenState = InterviewScreenState.ANSWER_SUBMITTING,
                    isRequestInFlight = true,
                )
            }
            submit(createSubmitCommand(audioRef = null, endRequest = request))
        }

        private fun beginFinishing(
            wrapUpPayload: String?,
            reportGenerating: Boolean,
        ) {
            this.wrapUpPayload = wrapUpPayload
            reduce {
                copy(
                    screenState = InterviewScreenState.FINISHING,
                    isRequestInFlight = false,
                    reportGenerating = reportGenerating,
                    showFinishConfirmation = false,
                    showEarlyExitWarning = false,
                    finalizationFailure = null,
                )
            }
            if (wrapUpPayload.isNullOrBlank()) {
                completeInterview()
            } else {
                startWrapUpRecording(wrapUpPayload)
            }
        }

        private fun startWrapUpRecording(payload: String) {
            val sessionId = state.value.sessionId ?: return
            wrapUpStartedAtMillis = state.value.elapsedMillis
            isWrapUpRecording = true
            startFinalizationWatchdog()
            sendEffect(
                InterviewEffect.StartRecordingSegment(
                    sessionId = sessionId,
                    type = InterviewMediaSegmentType.QUESTION_VIDEO,
                    questionId = null,
                    startedAtMillis = state.value.elapsedMillis,
                ),
            )
            sendEffect(InterviewEffect.PlayWrapUpMessage(payload))
        }

        @Suppress("TooGenericExceptionCaught")
        private fun finishWrapUpPlayback() {
            val sessionId = state.value.sessionId ?: return
            val startMillis = wrapUpStartedAtMillis ?: state.value.elapsedMillis
            viewModelScope.launch {
                try {
                    saveWrapUpRange(sessionId, startMillis, state.value.elapsedMillis)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emitGlobalError(GlobalAppEvent.ShowUnknownError)
                } finally {
                    sendEffect(InterviewEffect.StopRecordingSegment)
                }
            }
        }

        private fun completeInterview() {
            if (isFinalOutcomeEmitted) return
            timerJob?.cancel()
            val sessionId = state.value.sessionId ?: return
            if (state.value.reportGenerating) {
                reduce { copy(isUploadHandoffInProgress = true) }
                sendEffect(InterviewEffect.RequestUploadNotificationPermission)
            } else {
                emitFinalOutcome(InterviewEffect.InterviewEnded(completionReason, sessionId))
            }
        }

        private fun enqueueUpload(policy: InterviewUploadNetworkPolicy) {
            if (isFinalOutcomeEmitted) return
            val sessionId = state.value.sessionId ?: return
            lastUploadNetworkPolicy = policy
            reduce {
                copy(
                    isUploadHandoffInProgress = true,
                    finalizationFailure = null,
                )
            }
            sendEffect(InterviewEffect.EnqueueVideoUpload(sessionId, policy))
        }

        private fun finishUploadHandoff() {
            if (isFinalOutcomeEmitted) return
            val sessionId = state.value.sessionId ?: return
            reduce {
                copy(
                    isUploadHandoffInProgress = false,
                    isUploadEnqueued = true,
                    finalizationFailure = null,
                )
            }
            emitFinalOutcome(InterviewEffect.InterviewEnded(completionReason, sessionId))
        }

        private fun canOpenEndModal(): Boolean =
            turnStateMachine.pendingEndRequest == null &&
                state.value.screenState != InterviewScreenState.FINISHING

        private fun startFinalizationWatchdog() {
            finalizationWatchdogJob?.cancel()
            finalizationWatchdogJob =
                viewModelScope.launch {
                    delay(InterviewConstants.FINALIZATION_WATCHDOG_MILLIS)
                    if (!isFinalOutcomeEmitted && isWrapUpRecording) {
                        reduce {
                            copy(
                                finalizationFailure =
                                    InterviewFinalizationFailure.RECORDING_FINALIZATION_TIMEOUT,
                            )
                        }
                        sendEffect(InterviewEffect.StopRecordingSegment)
                    }
                }
        }

        private fun onRecordingFailure() {
            if (state.value.screenState != InterviewScreenState.FINISHING) {
                sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.MIC_DEVICE))
                return
            }
            if (isFinalOutcomeEmitted) return
            isWrapUpRecording = false
            finalizationWatchdogJob?.cancel()
            finalizationWatchdogJob = null
            reduce {
                copy(finalizationFailure = InterviewFinalizationFailure.RECORDING_FAILURE)
            }
        }

        private fun retryFinalization() {
            if (isFinalOutcomeEmitted) return
            when (state.value.finalizationFailure) {
                InterviewFinalizationFailure.RECORDING_FINALIZATION_TIMEOUT -> {
                    reduce { copy(finalizationFailure = null) }
                    startFinalizationWatchdog()
                    sendEffect(InterviewEffect.StopRecordingSegment)
                }

                InterviewFinalizationFailure.RECORDING_FAILURE -> {
                    val payload = wrapUpPayload ?: return
                    reduce { copy(finalizationFailure = null) }
                    startWrapUpRecording(payload)
                }

                InterviewFinalizationFailure.UPLOAD_HANDOFF_FAILURE -> {
                    val policy = lastUploadNetworkPolicy ?: return
                    enqueueUpload(policy)
                }

                null -> {
                    Unit
                }
            }
        }

        private fun exitFinalization() {
            if (state.value.finalizationFailure == null || isFinalOutcomeEmitted) return
            emitFinalOutcome(InterviewEffect.FinalizationExitConfirmed)
        }

        private fun emitFinalOutcome(effect: InterviewEffect) {
            if (isFinalOutcomeEmitted) return
            isFinalOutcomeEmitted = true
            finalizationWatchdogJob?.cancel()
            finalizationWatchdogJob = null
            reduce {
                copy(
                    isUploadHandoffInProgress = false,
                    finalizationFailure = null,
                )
            }
            sendEffect(effect)
        }

        private fun clearSubmissionBusyState() {
            turnStateMachine.abortSubmission()
            reduce { copy(isRequestInFlight = false) }
        }

        private fun beginFatalExit() {
            clearSubmissionBusyState()
            reduce {
                copy(
                    showFinishConfirmation = false,
                    showEarlyExitWarning = false,
                )
            }
            if (fatalExitJob != null) return
            fatalExitJob =
                viewModelScope.launch {
                    if (fatalExitPrompt.show() == GlobalModalResult.Confirm) {
                        sendEffect(InterviewEffect.FatalExitConfirmed)
                    }
                }
        }

        private fun onBackgrounded() {
            if (!state.value.screenState.isActiveInterview) return
            wasBackgrounded = true
            timerJob?.cancel()
            viewModelScope.launch { checkpointProgress() }
            sendEffect(InterviewEffect.StopRecordingSegment)
        }

        private fun onForegrounded() {
            if (!state.value.screenState.isActiveInterview || !wasBackgrounded) return
            wasBackgrounded = false
            connectionInterrupted = true
            viewModelScope.launch {
                checkpointProgress()
                sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.NETWORK))
            }
            startTicker()
        }

        private fun onNetworkDisconnected() {
            if (!state.value.screenState.isActiveInterview || connectionInterrupted) return
            connectionInterrupted = true
            viewModelScope.launch {
                checkpointProgress()
                sendEffect(InterviewEffect.StopRecordingSegment)
                sendEffect(InterviewEffect.NavigateToError(InterviewErrorType.NETWORK))
            }
        }

        private fun consumeRecoveryResult() {
            val result = recoveryStore.consume() ?: return
            val current = state.value
            if (!current.isCameraReady || !current.isMicrophoneReady || !current.hasEnoughStorage) {
                pendingRecoveryResult = result
                sendEffect(InterviewEffect.RequestCameraPermission)
                sendEffect(InterviewEffect.CheckStorageAvailability)
                return
            }
            applyRecoveryResult(result)
        }

        private fun tryApplyPendingRecovery() {
            val current = state.value
            val result = pendingRecoveryResult ?: return
            if (!current.isCameraReady || !current.isMicrophoneReady ||
                !current.hasEnoughStorage
            ) {
                return
            }
            pendingRecoveryResult = null
            applyRecoveryResult(result)
        }

        private fun applyRecoveryResult(result: InterviewRecoveryStore.Result) {
            connectionInterrupted = false
            wasBackgrounded = false
            completionReason = result.completionReason
            if (result.sessionEnded) {
                beginFinishing(result.wrapUpMessage?.ttsAudio, result.reportGenerating)
                return
            }
            val nextQuestion = result.nextQuestion ?: return
            reduce { copy(questionId = nextQuestion.questionId, isRequestInFlight = false) }
            resetTimeline()
            startQuestionSegmentAndPlayback()
            startTicker()
        }

        private fun resetTimeline() {
            questionAudioStartedAtMillis = null
            questionAudioEndedAtMillis = null
            answerStartedAtMillis = null
            answerEndedAtMillis = null
        }

        private fun handleFailure(
            error: Throwable,
            recoverableType: InterviewErrorType,
        ) {
            when (error) {
                is NetworkUnavailableException -> {
                    sendEffect(
                        InterviewEffect.NavigateToError(recoverableType),
                    )
                }

                is ServerException -> {
                    emitGlobalError(GlobalAppEvent.ShowServerErrorAndExit)
                }

                else -> {
                    emitGlobalError(GlobalAppEvent.ShowUnknownError)
                }
            }
        }

        private fun emitGlobalError(event: GlobalAppEvent) {
            viewModelScope.launch { GlobalErrorHandler.emit(event) }
        }

        private val Long.seconds: Float
            get() = this / MILLIS_PER_SECOND_FLOAT

        private val InterviewScreenState.isActiveInterview: Boolean
            get() =
                this == InterviewScreenState.QUESTION_PLAYING ||
                    this == InterviewScreenState.ANSWER_RECORDING ||
                    this == InterviewScreenState.ANSWER_SUBMITTING

        private companion object {
            const val MILLIS_PER_SECOND = 1_000L
            const val MILLIS_PER_SECOND_FLOAT = 1_000f
        }
    }
