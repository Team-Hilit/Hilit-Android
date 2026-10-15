package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand
import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.time.InterviewTimeCalculator
import com.dminus14.app.domain.usecase.DeleteInterviewMediaFileUseCase
import com.dminus14.app.domain.usecase.GetInterviewElapsedTimeUseCase
import com.dminus14.app.domain.usecase.SaveInterviewWrapUpRangeUseCase
import com.dminus14.app.domain.usecase.SavePendingInterviewAnswerUseCase
import com.dminus14.app.domain.usecase.StartInterviewTimerUseCase
import com.dminus14.app.domain.usecase.SubmitAnswerUseCase
import com.dminus14.app.feature.interview.InterviewConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterviewHandlerTest {
    @Test
    fun `취소된 제출은 보존 작업이 뒤늦게 끝나도 서버 요청을 시작하지 않는다`() =
        runTest {
            val fixture = InterviewViewModelTestFixture()
            val sessionId = InterviewViewModelTestFixture.SESSION_ID
            fixture.manifests[sessionId] = InterviewMediaManifest(sessionId = sessionId)
            val saveGate = CompletableDeferred<Unit>()
            val repository =
                object : InterviewLocalRepository by fixture.localRepository {
                    override suspend fun saveManifest(manifest: InterviewMediaManifest) {
                        withContext(NonCancellable) { saveGate.await() }
                        fixture.localRepository.saveManifest(manifest)
                    }
                }
            val context =
                RecordingInterviewContext(
                    backgroundScope,
                    InterviewState(sessionId = sessionId),
                )
            val handler =
                InterviewSubmissionHandler(
                    context = context,
                    submitAnswer = SubmitAnswerUseCase(fixture.interviewRepository),
                    savePendingAnswer = SavePendingInterviewAnswerUseCase(repository),
                    deleteMediaFile = DeleteInterviewMediaFileUseCase(repository),
                    turnStateMachine = InterviewTurnStateMachine(),
                    workflow = {},
                )
            val command =
                SubmitInterviewAnswerCommand(
                    sessionId = sessionId,
                    questionId = 25L,
                    isWrapUp = false,
                )
            handler.submit(command)
            runCurrent()

            context.jobs.single().cancel()
            saveGate.complete(Unit)
            runCurrent()

            assertTrue(fixture.submittedCommands.isEmpty())
            assertTrue(context.effects.isEmpty())
        }

    @Test
    fun `타이머 저장이 지연되어도 연속 시작 입력은 녹화와 질문 재생을 한 번만 시작한다`() =
        runTest {
            val fixture = InterviewViewModelTestFixture()
            val saveGate = CompletableDeferred<Unit>()
            val repository =
                object : InterviewLocalRepository by fixture.localRepository {
                    override suspend fun updateProgress(
                        transform: (InterviewProgress) -> InterviewProgress,
                    ): InterviewProgress? {
                        saveGate.await()
                        return fixture.localRepository.updateProgress(transform)
                    }
                }
            val context =
                RecordingInterviewContext(
                    backgroundScope,
                    InterviewState(
                        sessionId = 145L,
                        questionId = 25L,
                        screenState = InterviewScreenState.START_GUIDE,
                        isCameraPermissionGranted = true,
                        isCameraReady = true,
                        isMicrophoneReady = true,
                        isServerReady = true,
                        hasEnoughStorage = true,
                    ),
                )
            val events = mutableListOf<InterviewWorkflow>()
            val handler =
                InterviewTimingHandler(
                    context = context,
                    startTimer = StartInterviewTimerUseCase(repository, fixture.clock),
                    getElapsedTime =
                        GetInterviewElapsedTimeUseCase(
                            repository,
                            fixture.clock,
                            InterviewTimeCalculator(),
                        ),
                    timerCoordinator = InterviewTimerCoordinator(),
                    workflow = events::add,
                )

            handler.handle(InterviewIntent.Timing.StartInterview)
            runCurrent()
            handler.handle(InterviewIntent.Timing.StartInterview)
            saveGate.complete(Unit)
            runCurrent()

            assertEquals(1, context.recordings.size)
            assertEquals(1, events.filterIsInstance<InterviewWorkflow.PlayCurrentQuestion>().size)
            assertEquals(InterviewScreenState.QUESTION_PLAYING, context.state.value.screenState)
        }

    @Test
    fun `마무리 재시도는 이전 확정 후 녹화하고 종료를 한 번만 전달한다`() =
        runTest {
            val fixture = InterviewViewModelTestFixture()
            val context =
                RecordingInterviewContext(
                    backgroundScope,
                    InterviewState(sessionId = 145L),
                )
            val workflow = mutableListOf<InterviewWorkflow>()
            val handler =
                InterviewFinalizationHandler(
                    context = context,
                    saveWrapUpRange = SaveInterviewWrapUpRangeUseCase(fixture.localRepository),
                    workflow = workflow::add,
                )

            handler.beginFinishing("synthetic-wrap-up", reportGenerating = true)
            runCurrent()
            advanceTimeBy(InterviewConstants.FINALIZATION_WATCHDOG_MILLIS)
            runCurrent()
            assertEquals(
                InterviewFinalizationFailure.RECORDING_FINALIZATION_TIMEOUT,
                context.state.value.finalizationFailure,
            )

            handler.handle(InterviewIntent.Finalization.ClickRetryFinalization)
            assertEquals(1, context.recordings.size)
            handler.onWrapUpSegmentFinalized()
            assertEquals(2, context.recordings.size)
            assertFalse(context.isUploadPermissionRequested)

            handler.onWrapUpSegmentFinalized()
            assertTrue(context.isUploadPermissionRequested)
            handler.handle(InterviewIntent.Finalization.ReportVideoUploadEnqueued)
            handler.handle(InterviewIntent.Finalization.ReportVideoUploadEnqueued)
            handler.handle(InterviewIntent.Finalization.ReportVideoUploadEnqueueFailure)
            assertEquals(1, context.outcomes.size)
            assertTrue(context.state.value.isUploadEnqueued)
            assertEquals(null, context.state.value.finalizationFailure)
        }

    @Test
    fun `권한 변경은 현재 질문과 경과 시간 및 마무리 실패를 보존한다`() {
        val original =
            InterviewState(
                sessionId = 145L,
                questionId = 25L,
                elapsedMillis = 40_000L,
                isCameraPermissionGranted = true,
                isCameraReady = true,
                finalizationFailure = InterviewFinalizationFailure.UPLOAD_HANDOFF_FAILURE,
            )
        val mutation = InterviewMutation.CameraPermissionDenied(permanentlyDenied = true)

        val updated = InterviewReducer.reduce(original, mutation)

        assertEquals(
            original.copy(
                isCameraPermissionGranted = false,
                isCameraReady = false,
                permanentlyDeniedPermission = InterviewPermission.CAMERA,
            ),
            updated,
        )
        assertEquals(updated, InterviewReducer.reduce(original, mutation))
        assertTrue(original.isCameraReady)
    }
}

private class RecordingInterviewContext(
    private val scope: CoroutineScope,
    initialState: InterviewState,
) : MviContext<InterviewState, InterviewMutation, InterviewEffect> {
    override val state = MutableStateFlow(initialState)
    val effects = mutableListOf<InterviewEffect>()
    val jobs = mutableListOf<Job>()
    val recordings get() = effects.filterIsInstance<InterviewEffect.StartRecordingSegment>()
    val outcomes get() = effects.filterIsInstance<InterviewEffect.InterviewEnded>()
    val isUploadPermissionRequested
        get() = effects.contains(InterviewEffect.RequestUploadNotificationPermission)

    override fun dispatch(mutation: InterviewMutation) {
        state.value = InterviewReducer.reduce(state.value, mutation)
    }

    override fun sendEffect(effect: InterviewEffect) {
        effects += effect
    }

    override fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block).also { jobs += it }
}
