package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.common.modal.GlobalModalResult
import com.dminus14.app.domain.model.InterviewAbandon
import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewMediaOwnerType
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.InterviewSessionStatus
import com.dminus14.app.domain.model.InterviewSessionStatusType
import com.dminus14.app.domain.model.InterviewUploadTask
import com.dminus14.app.domain.model.SubmitAnswerResult
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand
import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.repository.InterviewRepository
import com.dminus14.app.domain.repository.InterviewWorkController
import com.dminus14.app.domain.time.InterviewClock
import com.dminus14.app.domain.time.InterviewTimeCalculator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.lang.reflect.Proxy
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

internal class InterviewViewModelTestFixture {
    var progress: InterviewProgress? = defaultProgress()
    var sessionStatus =
        InterviewSessionStatus(
            status = InterviewSessionStatusType.PROCESSING,
            startedAt = null,
            summaryQuestion = null,
        )
    var submitResult: SubmitAnswerResult? = null
    var abandonResult: InterviewAbandon? = null
    val manifests = mutableMapOf<Long, InterviewMediaManifest>()
    val submittedCommands = mutableListOf<SubmitInterviewAnswerCommand>()
    val deletedMedia = mutableListOf<InterviewMediaFileRef>()
    val uploadTasks = mutableMapOf<String, InterviewUploadTask>()
    val recoveryStore = InterviewRecoveryStore()
    var saveManifestFailure: Throwable? = null
    var successfulManifestSavesBeforeFailure: Int = 0
    var fatalPromptResult: GlobalModalResult = GlobalModalResult.Confirm
    var fatalPromptCount = 0
    private var mediaSequence = 0

    val clock =
        object : InterviewClock {
            override fun currentEpochMillis(): Long = NOW_EPOCH_MILLIS

            override fun elapsedRealtimeMillis(): Long = NOW_REALTIME_MILLIS
        }

    private val workController =
        object : InterviewWorkController {
            override suspend fun enqueueUpload(
                uploadTaskId: String,
                networkPolicy: com.dminus14.app.domain.model.InterviewUploadNetworkPolicy,
            ) = Unit

            override suspend fun enqueueRetentionCleanup(deadlineEpochMillis: Long) = Unit

            override suspend fun isUploadRunningOrPending(uploadTaskId: String): Boolean = false

            override suspend fun cancelUpload(uploadTaskId: String) = Unit

            override suspend fun cancelAll() = Unit
        }

    val localRepository: InterviewLocalRepository =
        proxy { name, args ->
            when (name) {
                "getProgress" -> {
                    progress
                }

                "saveProgress" -> {
                    Unit.also { progress = args[0] as InterviewProgress }
                }

                "updateProgress" -> {
                    @Suppress("UNCHECKED_CAST")
                    val transform = args[0] as (InterviewProgress) -> InterviewProgress
                    progress?.let(transform).also { progress = it }
                }

                "clearProgress" -> {
                    Unit.also { progress = null }
                }

                "getManifest" -> {
                    manifests[args[0] as Long]
                }

                "getUploadManifest" -> {
                    null
                }

                "saveManifest" -> {
                    saveManifestFailure?.let { failure ->
                        if (successfulManifestSavesBeforeFailure == 0) throw failure
                        successfulManifestSavesBeforeFailure -= 1
                    }
                    val manifest = args[0] as InterviewMediaManifest
                    Unit.also { manifests[manifest.sessionId] = manifest }
                }

                "createMediaFile" -> {
                    val sessionId = args[0] as Long
                    val type = args[1] as InterviewMediaSegmentType
                    mediaSequence += 1
                    InterviewMediaFileRef(
                        value =
                            UUID
                                .nameUUIDFromBytes(
                                    "media-$mediaSequence".toByteArray(),
                                ).toString(),
                        ownerType = InterviewMediaOwnerType.SESSION,
                        ownerId = sessionId.toString(),
                        segmentType = type,
                    )
                }

                "createUploadMediaFile" -> {
                    error("사용하지 않는 테스트 호출: $name")
                }

                "deleteMediaFile" -> {
                    Unit.also { deletedMedia += args[0] as InterviewMediaFileRef }
                }

                "handoffUploadTask" -> {
                    val task = args[0] as InterviewUploadTask
                    Unit.also {
                        uploadTasks[task.uploadTaskId] = task
                        progress = null
                    }
                }

                "getUploadTask" -> {
                    uploadTasks[args[0] as String]
                }

                "saveUploadTask" -> {
                    val task = args[0] as InterviewUploadTask
                    Unit.also { uploadTasks[task.uploadTaskId] = task }
                }

                "getUploadTasks" -> {
                    uploadTasks.values.toList()
                }

                "deleteUploadTask" -> {
                    Unit.also { uploadTasks.remove(args[0] as String) }
                }

                "deleteSession" -> {
                    val sessionId = args[0] as Long
                    Unit.also {
                        manifests.remove(sessionId)
                        if (progress?.sessionId == sessionId) progress = null
                    }
                }

                "clearAll" -> {
                    Unit.also {
                        manifests.clear()
                        uploadTasks.clear()
                        progress = null
                    }
                }

                "isCleanupPending" -> {
                    false
                }

                "setCleanupPending" -> {
                    Unit
                }

                else -> {
                    error("사용하지 않는 테스트 호출: $name")
                }
            }
        }

    val interviewRepository: InterviewRepository =
        proxy { name, args ->
            when (name) {
                "getInterviewSession", "getInterviewSessionStatus" -> {
                    sessionStatus
                }

                "getAudioStreamUrl" -> {
                    "https://example.test/question/${args[1]}"
                }

                "submitAnswer" -> {
                    val command = args[0] as SubmitInterviewAnswerCommand
                    submittedCommands += command
                    checkNotNull(submitResult)
                }

                "abandon" -> {
                    checkNotNull(abandonResult)
                }

                else -> {
                    error("사용하지 않는 테스트 호출: $name")
                }
            }
        }

    fun createViewModel(): InterviewViewModel {
        val calculator = InterviewTimeCalculator()
        return InterviewViewModel(
            cleanupExpiredData =
                CleanupExpiredInterviewDataUseCase(
                    localRepository,
                    workController,
                    clock,
                    calculator,
                ),
            getProgress = GetInterviewProgressUseCase(localRepository),
            getSession = GetInterviewSessionUseCase(interviewRepository),
            startTimer = StartInterviewTimerUseCase(localRepository, clock),
            getElapsedTime = GetInterviewElapsedTimeUseCase(localRepository, clock, calculator),
            getMediaManifest = GetInterviewMediaManifestUseCase(localRepository),
            checkpointProgress =
                CheckpointInterviewProgressUseCase(localRepository, clock, calculator),
            getQuestionAudioUrl = GetQuestionAudioStreamUrlUseCase(interviewRepository),
            createMediaSegment = CreateInterviewMediaSegmentUseCase(localRepository),
            finalizeMediaSegment = FinalizeInterviewMediaSegmentUseCase(localRepository),
            submitAnswer = SubmitAnswerUseCase(interviewRepository),
            deleteMediaFile = DeleteInterviewMediaFileUseCase(localRepository),
            savePendingAnswer = SavePendingInterviewAnswerUseCase(localRepository),
            saveWrapUpRange = SaveInterviewWrapUpRangeUseCase(localRepository),
            deleteSession = DeleteInterviewSessionUseCase(localRepository),
            recoveryStore = recoveryStore,
            timerCoordinator = InterviewTimerCoordinator(),
            turnStateMachine = InterviewTurnStateMachine(),
            fatalExitPrompt =
                object : InterviewFatalExitPrompt() {
                    override suspend fun show(): GlobalModalResult {
                        fatalPromptCount += 1
                        return fatalPromptResult
                    }
                },
        )
    }

    companion object {
        const val SESSION_ID = 145L
        const val NOW_EPOCH_MILLIS = 1_000_000L
        const val NOW_REALTIME_MILLIS = 50_000L

        fun defaultProgress() =
            InterviewProgress(
                sessionId = SESSION_ID,
                retentionDeadlineEpochMillis = NOW_EPOCH_MILLIS + 86_400_000L,
                retentionRemainingAtCheckpointMillis = 86_400_000L,
                retentionCheckpointElapsedRealtimeMillis = NOW_REALTIME_MILLIS,
                timerStartedAtEpochMillis = null,
                elapsedAtCheckpointMillis = null,
                checkpointedAtEpochMillis = null,
                elapsedCheckpointElapsedRealtimeMillis = null,
            )
    }
}

private inline fun <reified T> proxy(
    crossinline handler: (name: String, args: Array<out Any?>) -> Any?,
): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "${T::class.simpleName}TestDouble"
            else -> handler(method.name, args.orEmpty())
        }
    } as T
