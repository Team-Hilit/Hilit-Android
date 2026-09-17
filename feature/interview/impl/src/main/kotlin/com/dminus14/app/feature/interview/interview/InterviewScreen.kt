@file:Suppress("TooManyFunctions", "LongMethod", "CyclomaticComplexMethod")

package com.dminus14.app.feature.interview.interview

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dminus14.app.core.permission.AppPermission
import com.dminus14.app.core.permission.rememberAppSettingsLauncher
import com.dminus14.app.feature.interview.api.InterviewErrorType
import com.dminus14.app.feature.interview.component.InterviewAbortModal
import com.dminus14.app.feature.interview.component.InterviewCameraPreview
import com.dminus14.app.feature.interview.component.InterviewFinalizationFailureModal
import com.dminus14.app.feature.interview.component.InterviewFinishModal
import com.dminus14.app.feature.interview.component.InterviewMeteredUploadModal
import com.dminus14.app.feature.interview.interview.layer.InterviewScreenBaseLayer
import com.dminus14.app.feature.interview.interview.layer.InterviewScreenOngoingLayer
import com.dminus14.app.feature.interview.interview.layer.InterviewScreenPrepareLayer
import com.dminus14.designsystem.theme.HilitTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException

@Composable
fun InterviewScreen(
    modifier: Modifier = Modifier,
    onNavigateHome: () -> Unit = {},
    onNavigateError: (InterviewErrorType) -> Unit = {},
    onInterviewEnded: (InterviewCompletionReason, Long) -> Unit = { _, _ -> },
    viewModel: InterviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val entryPoint =
        remember(context.applicationContext) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                InterviewScreenEntryPoint::class.java,
            )
        }
    val permissionManager = remember(entryPoint) { entryPoint.permissionManager() }
    val effectHandler = remember(entryPoint) { entryPoint.effectHandler() }

    DisposableEffect(effectHandler) {
        onDispose(effectHandler::release)
    }

    val microphonePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.onIntent(InterviewIntent.ReportMicrophoneReady)
            } else {
                viewModel.onIntent(
                    InterviewIntent.ReportMicrophonePermissionDenied(
                        permanentlyDenied =
                            activity?.let {
                                !ActivityCompat.shouldShowRequestPermissionRationale(
                                    it,
                                    Manifest.permission.RECORD_AUDIO,
                                )
                            } == true,
                    ),
                )
            }
        }
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.onIntent(InterviewIntent.ReportCameraPermissionGranted)
            } else {
                viewModel.onIntent(
                    InterviewIntent.ReportCameraPermissionDenied(
                        permanentlyDenied =
                            activity?.let {
                                !ActivityCompat.shouldShowRequestPermissionRationale(
                                    it,
                                    Manifest.permission.CAMERA,
                                )
                            } == true,
                    ),
                )
            }
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onIntent(InterviewIntent.ReportUploadNotificationPermission(granted))
        }
    val openSettings =
        permissionManager.rememberAppSettingsLauncher(
            onGrant = {
                when (state.permanentlyDeniedPermission) {
                    InterviewPermission.CAMERA -> {
                        viewModel.onIntent(
                            InterviewIntent.ReportCameraPermissionGranted,
                        )
                    }

                    InterviewPermission.MICROPHONE -> {
                        viewModel.onIntent(
                            InterviewIntent.ReportMicrophoneReady,
                        )
                    }

                    null -> {
                        Unit
                    }
                }
            },
        )

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        viewModel.onIntent(
                            InterviewIntent.ReportAppForegrounded,
                        )
                    }

                    Lifecycle.Event.ON_STOP -> {
                        viewModel.onIntent(
                            InterviewIntent.ReportAppBackgrounded,
                        )
                    }

                    else -> {
                        Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(entryPoint) {
        var firstNetworkValue = true
        entryPoint.networkMonitor().isConnected.collect { connected ->
            if (firstNetworkValue) {
                firstNetworkValue = false
            } else {
                viewModel.onIntent(
                    if (connected) {
                        InterviewIntent.ReportNetworkRestored
                    } else {
                        InterviewIntent.ReportNetworkDisconnected
                    },
                )
            }
        }
    }

    LaunchedEffect(state.countdownSeconds) {
        if (state.countdownSeconds != null) effectHandler.playCountdownTone()
    }

    LaunchedEffect(viewModel) {
        viewModel.onIntent(InterviewIntent.LoadInterview)
        viewModel.onIntent(InterviewIntent.ConsumeRecoveryResult)
        viewModel.effect.collect { effect ->
            when (effect) {
                InterviewEffect.RequestCameraPermission -> {
                    if (permissionManager.isGranted(AppPermission.CAMERA)) {
                        viewModel.onIntent(InterviewIntent.ReportCameraPermissionGranted)
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }

                InterviewEffect.RequestMicrophonePermission -> {
                    if (permissionManager.isGranted(AppPermission.RECORD_AUDIO)) {
                        viewModel.onIntent(InterviewIntent.ReportMicrophoneReady)
                    } else {
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                is InterviewEffect.OpenAppSettings -> {
                    openSettings(
                        when (effect.permission) {
                            InterviewPermission.CAMERA -> AppPermission.CAMERA
                            InterviewPermission.MICROPHONE -> AppPermission.RECORD_AUDIO
                        },
                    )
                }

                InterviewEffect.CheckStorageAvailability -> {
                    effectHandler.checkStorage(viewModel::onIntent)
                }

                is InterviewEffect.StartRecordingSegment -> {
                    effectHandler.startRecording(effect, viewModel::onIntent)
                }

                InterviewEffect.StopRecordingSegment -> {
                    effectHandler.stopRecording()
                }

                InterviewEffect.PauseRecording -> {
                    effectHandler.pauseRecording()
                }

                InterviewEffect.ResumeRecording -> {
                    effectHandler.resumeRecording()
                }

                is InterviewEffect.PlayQuestionAudio -> {
                    effectHandler.playQuestion(effect.url, viewModel::onIntent)
                }

                is InterviewEffect.PlayWrapUpMessage -> {
                    effectHandler.playWrapUp(effect.payload, viewModel::onIntent)
                }

                is InterviewEffect.ExportAnswerAudio -> {
                    effectHandler.exportAnswerAudio(effect, viewModel::onIntent)
                }

                InterviewEffect.ShowEarlyFinishAvailable -> {
                    Toast.makeText(context, "이제 면접을 종료할 수 있어요", Toast.LENGTH_LONG).show()
                }

                InterviewEffect.PlayFinalCountdown -> {
                    Toast.makeText(context, "면접이 곧 마무리돼요", Toast.LENGTH_LONG).show()
                }

                InterviewEffect.RequestUploadNotificationPermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                    } else {
                        viewModel.onIntent(InterviewIntent.ReportUploadNotificationPermission(true))
                    }
                }

                InterviewEffect.ShowUploadNotificationPermissionDenied -> {
                    Toast
                        .makeText(
                            context,
                            "알림 권한이 꺼져 있어 업로드 진행 상태를 알려드릴 수 없어요",
                            Toast.LENGTH_LONG,
                        ).show()
                }

                InterviewEffect.CheckUploadNetwork -> {
                    viewModel.onIntent(
                        InterviewIntent.ReportUploadNetworkMetered(
                            effectHandler.isUploadNetworkMetered(),
                        ),
                    )
                }

                is InterviewEffect.EnqueueVideoUpload -> {
                    runCatching {
                        effectHandler.enqueueUpload(
                            effect.sessionId,
                            effect.networkPolicy,
                        )
                    }.onSuccess { viewModel.onIntent(InterviewIntent.ReportVideoUploadEnqueued) }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            viewModel.onIntent(
                                InterviewIntent.ReportVideoUploadEnqueueFailure,
                            )
                        }
                }

                is InterviewEffect.NavigateToError -> {
                    onNavigateError(effect.errorType)
                }

                InterviewEffect.PermissionDeniedExitRequested,
                InterviewEffect.PrerequisiteMissing,
                InterviewEffect.FatalExitConfirmed,
                InterviewEffect.FinalizationExitConfirmed,
                -> {
                    onNavigateHome()
                }

                is InterviewEffect.InterviewEnded -> {
                    onInterviewEnded(effect.reason, effect.sessionId)
                }
            }
        }
    }

    BackHandler {
        if (state.screenState == InterviewScreenState.DEVICE_CHECK ||
            state.screenState == InterviewScreenState.QUESTION_PREPARING ||
            state.screenState == InterviewScreenState.START_GUIDE
        ) {
            viewModel.onIntent(InterviewIntent.ClickPermissionDeniedBack)
        } else if (state.screenState != InterviewScreenState.FINISHING) {
            viewModel.onIntent(InterviewIntent.ClickExitInterview)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        InterviewCameraPreview(
            isCameraPermissionGranted = state.isCameraPermissionGranted,
            videoCapture = effectHandler.videoCapture,
            onCameraReady = { viewModel.onIntent(InterviewIntent.ReportCameraReady) },
            onCameraBindingFailed = {
                viewModel.onIntent(InterviewIntent.ReportCameraBindingFailure)
            },
            modifier = Modifier.fillMaxSize(),
        )
        InterviewContent(
            state = state,
            onIntent = viewModel::onIntent,
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (state.showFinishConfirmation) {
        InterviewFinishModal(
            onContinueClick = { viewModel.onIntent(InterviewIntent.DismissFinishInterview) },
            onFinishClick = { viewModel.onIntent(InterviewIntent.ConfirmFinishInterview) },
        )
    }
    if (state.showEarlyExitWarning) {
        InterviewAbortModal(
            onExitClick = { viewModel.onIntent(InterviewIntent.ConfirmEarlyExit) },
            onContinueClick = { viewModel.onIntent(InterviewIntent.DismissEarlyExit) },
        )
    }
    if (state.showMeteredUploadConfirmation) {
        InterviewMeteredUploadModal(
            onUseMobileData = { viewModel.onIntent(InterviewIntent.ConfirmMeteredUpload) },
            onWaitForWifi = { viewModel.onIntent(InterviewIntent.DismissMeteredUpload) },
        )
    }
    if (state.finalizationFailure != null) {
        InterviewFinalizationFailureModal(
            onExitClick = { viewModel.onIntent(InterviewIntent.ClickExitFinalization) },
            onRetryClick = { viewModel.onIntent(InterviewIntent.ClickRetryFinalization) },
        )
    }
}

@Composable
fun InterviewContent(
    state: InterviewState,
    onIntent: (InterviewIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        InterviewScreenBaseLayer(modifier = Modifier.fillMaxSize())

        when (state.screenState) {
            InterviewScreenState.DEVICE_CHECK,
            InterviewScreenState.QUESTION_PREPARING,
            InterviewScreenState.START_GUIDE,
            -> {
                InterviewScreenPrepareLayer(
                    isReady = state.isReadyToStart,
                    isPermissionGranted =
                        state.isCameraPermissionGranted && state.isMicrophoneReady,
                    interviewScreenState = state.screenState,
                    showOpenSettings = state.permanentlyDeniedPermission != null,
                    hasEnoughStorage = state.hasEnoughStorage,
                    onInterviewStart = { onIntent(InterviewIntent.StartInterview) },
                    onPermissionDeniedBack = {
                        onIntent(InterviewIntent.ClickPermissionDeniedBack)
                    },
                    onOpenSettings = { onIntent(InterviewIntent.ClickOpenSettings) },
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                )
            }

            InterviewScreenState.QUESTION_PLAYING,
            InterviewScreenState.ANSWER_RECORDING,
            InterviewScreenState.ANSWER_SUBMITTING,
            InterviewScreenState.FINISHING,
            -> {
                InterviewScreenOngoingLayer(
                    interviewSpeaker = state.speaker,
                    screenState = state.screenState,
                    elapsedSeconds = state.elapsedSeconds,
                    countdownSeconds = state.countdownSeconds,
                    canFinishEarly = state.canFinishEarly,
                    hasSpeechStarted = state.hasSpeechStarted,
                    isQuestionAudioRetryVisible = state.isQuestionAudioRetryVisible,
                    onRetryQuestion = { onIntent(InterviewIntent.ClickRetryQuestionAudio) },
                    onFinishAnswer = { onIntent(InterviewIntent.ClickFinishAnswer) },
                    onFinishRequest = { onIntent(InterviewIntent.ClickFinishInterview) },
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                )
            }
        }
    }
}

@Preview(widthDp = 375, heightDp = 812)
@Composable
private fun InterviewScreenPreparingPreview() {
    HilitTheme { InterviewContent(state = InterviewState(), onIntent = {}) }
}

@Preview(widthDp = 375, heightDp = 812)
@Composable
private fun InterviewScreenPreparedPreview() {
    HilitTheme {
        InterviewContent(
            state =
                InterviewState(
                    screenState = InterviewScreenState.START_GUIDE,
                    isCameraPermissionGranted = true,
                    isCameraReady = true,
                    isMicrophoneReady = true,
                    isServerReady = true,
                    hasEnoughStorage = true,
                ),
            onIntent = {},
        )
    }
}

@Preview(widthDp = 375, heightDp = 812)
@Composable
private fun InterviewScreenOngoingPreview() {
    HilitTheme {
        InterviewContent(
            state =
                InterviewState(
                    screenState = InterviewScreenState.ANSWER_RECORDING,
                    elapsedMillis = 72_000L,
                    hasSpeechStarted = true,
                ),
            onIntent = {},
        )
    }
}
