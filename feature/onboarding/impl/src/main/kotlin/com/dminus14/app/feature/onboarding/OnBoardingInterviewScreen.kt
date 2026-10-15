package com.dminus14.app.feature.onboarding

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dminus14.app.feature.interview.api.InterviewRoute
import com.dminus14.app.feature.login.api.SuspensionNotice
import com.dminus14.app.feature.onboarding.component.OnBoardingExistingPortfolioModals
import com.dminus14.app.feature.onboarding.component.OnBoardingJobDescriptionStep
import com.dminus14.app.feature.onboarding.component.OnBoardingMainProjectStep
import com.dminus14.app.feature.onboarding.component.OnBoardingPortfolioStep
import com.dminus14.app.feature.onboarding.component.OnBoardingPortfolioStepUiState
import com.dminus14.app.feature.onboarding.component.OnBoardingPreloadStep
import com.dminus14.designsystem.component.button.HilitFixedBottomDualButton
import com.dminus14.designsystem.component.progressbar.HilitProgressBar
import com.dminus14.designsystem.component.topbar.HilitIconTopBar
import com.dminus14.designsystem.component.topbar.HilitTextTopBar
import com.dminus14.designsystem.component.topbar.TopBarType
import com.dminus14.designsystem.theme.HilitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.Flow as FlowIntent
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.Portfolio as PortfolioIntent

private val ContentHorizontalPadding = 20.dp
private val ProgressBarHorizontalPadding = 20.dp
private val ProgressBarVerticalPadding = 4.dp
private const val MIME_PDF = "application/pdf"

@Composable
fun OnBoardingInterviewScreen(
    onNavigate: (Any) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnBoardingInterviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val portfolioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val picked = withContext(Dispatchers.IO) { copyPdfToCache(context, uri) }
                if (picked != null) {
                    viewModel.onIntent(
                        PortfolioIntent.PortfolioFileSelected(
                            file = picked.file,
                            fileName = picked.name,
                        ),
                    )
                }
            }
        }

    LaunchedEffect(Unit) {
        viewModel.onIntent(FlowIntent.Load)
    }

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                OnBoardingInterviewEffect.CloseRequested -> {
                    onClose()
                }

                OnBoardingInterviewEffect.LaunchPortfolioPicker -> {
                    portfolioPickerLauncher.launch(arrayOf(MIME_PDF))
                }

                is OnBoardingInterviewEffect.NavigateToResult -> {
                    // sessionId 는 Interview 화면이 로컬 InterviewProgress 에서 조회한다.
                    // 여기서 raw Long 을 backStack 에 넘기면 Nav3 가 destination 매칭 실패로 크래시.
                    onNavigate(InterviewRoute)
                }

                OnBoardingInterviewEffect.NavigateToSuspensionNotice -> {
                    onNavigate(SuspensionNotice)
                }
            }
        }
    }

    LaunchedEffect(state.step) {
        if (state.step == OnBoardingInterviewStep.Portfolio) {
            viewModel.onIntent(PortfolioIntent.PortfolioStepEntered)
        }
    }

    OnBoardingInterviewContent(
        state = state,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

private data class PickedPdf(
    val file: File,
    val name: String,
)

/**
 * SAF로 고른 PDF를 앱 캐시로 복사한다. 업로드 UseCase는 [File]을 요구하기 때문이다.
 *
 * 캐시 파일 이름은 SAF display name(문서 공급자 통제, 신뢰 불가)이 아니라
 * [File.createTempFile]이 만든 랜덤값을 사용한다. `../` 나 절대 경로가 섞인 display name으로
 * 앱 전용 저장소의 다른 파일을 덮어쓰는 path traversal(CWE-22) 취약점을 원천 차단한다.
 * 사용자·서버가 보는 원본 이름은 [PickedPdf.name] 문자열로만 별도 보관한다.
 */
private fun copyPdfToCache(
    context: Context,
    uri: Uri,
): PickedPdf? {
    val resolver = context.contentResolver
    val displayName = resolver.queryDisplayName(uri) ?: "portfolio.pdf"
    return runCatching {
        val target = File.createTempFile("portfolio-", ".pdf", context.cacheDir)
        val copied =
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
        if (copied) {
            PickedPdf(file = target, name = displayName)
        } else {
            target.delete()
            null
        }
    }.getOrNull()
}

private fun android.content.ContentResolver.queryDisplayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

@Composable
internal fun OnBoardingInterviewContent(
    state: OnBoardingInterviewState,
    onIntent: (OnBoardingInterviewIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 모든 스텝 공통으로 BackHandler를 등록해 시스템 back이 온보딩 전체를 이탈시키지 않게 한다.
    // Preload 스텝에서는 VM의 onPreviousClick 이 no-op로 처리해(세션 이중 생성 방지),
    // 화면은 그대로 유지된다.
    BackHandler {
        onIntent(FlowIntent.ClickPrevious)
    }

    if (state.step == OnBoardingInterviewStep.Preload) {
        OnBoardingPreloadStep(
            basicInfoStatus = state.loadingBasicInfo,
            jdStatus = state.loadingJd,
            portfolioStatus = state.loadingPortfolio,
            onIntent = onIntent,
            modifier = modifier,
        )
        // Preload 중 세션 생성 실패는 화면에 노출되지 않던 버그를 다이얼로그로 안내한다.
        // 확인 시 MainProject 스텝으로 되돌려 재시도 가능하게 만든다.
        if (state.errorMessage != null) {
            OnBoardingPreloadFailureDialog(
                message = state.errorMessage,
                onIntent = onIntent,
            )
        }
        return
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(HilitTheme.colors.hilitWhite),
    ) {
        OnBoardingInterviewHeader(
            step = state.step,
            onCloseClick = { onIntent(FlowIntent.ClickClose) },
            onSkipClick = { onIntent(FlowIntent.ClickSkip) },
        )

        OnBoardingInterviewAnimatedStepContent(
            state = state,
            onIntent = onIntent,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        HilitFixedBottomDualButton(
            leftText = "이전으로",
            rightText = "계속하기",
            leftEnabled = state.isBottomBarEnabled(),
            rightEnabled = state.isBottomBarEnabled() && state.isContinueEnabled(),
            onLeftClick = { onIntent(FlowIntent.ClickPrevious) },
            onRightClick = { onIntent(FlowIntent.ClickContinue) },
        )
    }

    if (state.showRelevanceFailDialog) {
        OnBoardingRelevanceFailDialog(onIntent = onIntent)
    }

    OnBoardingExistingPortfolioModals(
        state = state,
        onIntent = onIntent,
    )
}

@Composable
private fun OnBoardingInterviewAnimatedStepContent(
    state: OnBoardingInterviewState,
    onIntent: (OnBoardingInterviewIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = state.step,
        modifier = modifier,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            if (forward) {
                (
                    slideInHorizontally { fullWidth -> fullWidth } + fadeIn()
                ) togetherWith (
                    slideOutHorizontally { fullWidth -> -fullWidth } + fadeOut()
                )
            } else {
                (
                    slideInHorizontally { fullWidth -> -fullWidth } + fadeIn()
                ) togetherWith (
                    slideOutHorizontally { fullWidth -> fullWidth } + fadeOut()
                )
            }
        },
        label = "onBoardingInterviewStep",
    ) { step ->
        OnBoardingInterviewStepContent(
            step = step,
            state = state,
            onIntent = onIntent,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = ContentHorizontalPadding),
        )
    }
}

@Composable
private fun OnBoardingInterviewHeader(
    step: OnBoardingInterviewStep,
    onCloseClick: () -> Unit,
    onSkipClick: () -> Unit,
) {
    when (step) {
        OnBoardingInterviewStep.Portfolio -> {
            HilitIconTopBar(
                type = TopBarType.HideRight,
                title = "",
                onLeftClick = onCloseClick,
            )
        }

        OnBoardingInterviewStep.JobDescription,
        OnBoardingInterviewStep.MainProject,
        OnBoardingInterviewStep.Preload,
        -> {
            HilitTextTopBar(
                type = TopBarType.HideMiddle,
                buttonText = "건너뛰기",
                onLeftClick = onCloseClick,
                onButtonClick = onSkipClick,
            )
        }
    }

    HilitProgressBar(
        step = step.toProgressStep(),
        maxStep = PROGRESS_MAX_STEP,
        modifier =
            Modifier
                .padding(horizontal = ProgressBarHorizontalPadding)
                .padding(vertical = ProgressBarVerticalPadding),
    )
}

@Composable
private fun OnBoardingInterviewStepContent(
    step: OnBoardingInterviewStep,
    state: OnBoardingInterviewState,
    onIntent: (OnBoardingInterviewIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (step) {
        OnBoardingInterviewStep.JobDescription -> {
            OnBoardingJobDescriptionStep(
                tab = state.jobDescriptionTab,
                link = state.jobDescriptionLink,
                linkStatus = state.jdLinkStatus,
                linkSubText = state.jdLinkSubText,
                text = state.jobDescriptionText,
                onIntent = onIntent,
                modifier = modifier,
            )
        }

        OnBoardingInterviewStep.Portfolio -> {
            OnBoardingPortfolioStep(
                uiState =
                    OnBoardingPortfolioStepUiState(
                        fileName = state.portfolioFileName,
                        isProcessing = state.isPortfolioProcessing,
                        uploadProgress = state.portfolioUploadProgress,
                        errorMessage = state.portfolioErrorMessage,
                    ),
                onIntent = onIntent,
                modifier = modifier,
            )
        }

        OnBoardingInterviewStep.MainProject -> {
            OnBoardingMainProjectStep(
                text = state.mainProjectText,
                error = state.mainProjectError,
                onIntent = onIntent,
                modifier = modifier,
            )
        }

        OnBoardingInterviewStep.Preload -> {
            Unit
        }
    }
}
