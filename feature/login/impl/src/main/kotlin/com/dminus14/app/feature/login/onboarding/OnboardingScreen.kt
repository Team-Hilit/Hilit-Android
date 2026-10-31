package com.dminus14.app.feature.login.onboarding

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dminus14.app.feature.home.api.Home
import com.dminus14.app.feature.login.onboarding.component.OnboardingBottomBar
import com.dminus14.app.feature.login.onboarding.component.OnboardingExperienceStep
import com.dminus14.app.feature.login.onboarding.component.OnboardingJobStep
import com.dminus14.app.feature.login.onboarding.component.OnboardingNamingStep
import com.dminus14.app.feature.login.onboarding.component.OnboardingRegisterDoneStep
import com.dminus14.designsystem.component.icon.HilitIconAsset
import com.dminus14.designsystem.component.progressbar.HilitProgressBar
import com.dminus14.designsystem.component.topbar.HilitIconTopBar
import com.dminus14.designsystem.component.topbar.TopBarType
import com.dminus14.designsystem.theme.HilitTheme
import kotlinx.coroutines.flow.first

private val ContentHorizontalPadding = 20.dp
private val IndicatorHorizontalPadding = 20.dp
private val IndicatorTopSpacing = 8.dp
private val ContentTopSpacing = 24.dp
private const val PROGRESS_MAX_STEP = 3
private const val PROGRESS_STEP_NAMING = 1
private const val PROGRESS_STEP_JOB = 2
private const val PROGRESS_STEP_EXPERIENCE = 3

@Composable
fun OnboardingScreen(
    onNavigate: (Any) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler {
        viewModel.onIntent(OnboardingIntent.CloseClick)
    }

    LaunchedEffect(Unit) {
        viewModel.onIntent(OnboardingIntent.Load)
    }

    LaunchedEffect(Unit) {
        viewModel.effect.first { effect ->
            when (effect) {
                OnboardingEffect.CloseRequested -> {
                    onClose()
                }

                OnboardingEffect.Completed -> {
                    onNavigate(Home)
                }

                is OnboardingEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
                }
            }
            // 닫은 화면의 늦은 제출 결과가 추가 이동을 유발하지 않도록 수집을 끝낸다.
            effect == OnboardingEffect.CloseRequested
        }
    }

    OnboardingContent(
        state = state,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

@Composable
internal fun OnboardingContent(
    state: OnboardingState,
    onIntent: (OnboardingIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(HilitTheme.colors.hilitWhite)
                // 키보드가 열리면 헤더~하단 버튼 전체를 IME 위로 올린다.
                // 스텝 내부 필드는 weight 영역 안에서 자연스럽게 재중앙 정렬된다.
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    },
                ),
    ) {
        OnboardingHeader(
            step = state.step,
            onCloseClick = { onIntent(OnboardingIntent.CloseClick) },
        )

        OnboardingStepPager(
            state = state,
            onIntent = onIntent,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        )

        OnboardingBottomBar(
            step = state.step,
            isContinueEnabled = state.isContinueEnabled,
            onIntent = onIntent,
        )
    }
}

@Composable
private fun OnboardingStepPager(
    state: OnboardingState,
    onIntent: (OnboardingIntent) -> Unit,
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
        label = "onboardingStep",
    ) { step ->
        OnboardingStepContent(
            step = step,
            state = state,
            onIntent = onIntent,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = ContentHorizontalPadding)
                    .padding(
                        top =
                            if (step == OnboardingStep.RegisterDone) {
                                0.dp
                            } else {
                                ContentTopSpacing
                            },
                    ),
        )
    }
}

@Composable
private fun OnboardingHeader(
    step: OnboardingStep,
    onCloseClick: () -> Unit,
) {
    if (step == OnboardingStep.RegisterDone) return

    HilitIconTopBar(
        type = TopBarType.HideRight,
        leftIcon = HilitIconAsset.Cancel,
        title = "",
        onLeftClick = onCloseClick,
    )

    HilitProgressBar(
        step = step.toProgressStep(),
        maxStep = PROGRESS_MAX_STEP,
        modifier =
            Modifier
                .padding(horizontal = IndicatorHorizontalPadding)
                .padding(top = IndicatorTopSpacing),
    )
}

@Composable
private fun OnboardingStepContent(
    step: OnboardingStep,
    state: OnboardingState,
    onIntent: (OnboardingIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (step) {
        OnboardingStep.Naming -> {
            OnboardingNamingStep(
                name = state.name,
                onNameChange = { onIntent(OnboardingIntent.NameChange(it)) },
                modifier = modifier,
            )
        }

        OnboardingStep.JobSelection -> {
            OnboardingJobStep(
                userName = state.name,
                jobs = state.jobs.map { it.label },
                selectedJobIndex = state.selectedJobIndex,
                onJobClick = { onIntent(OnboardingIntent.JobClick(it)) },
                modifier = modifier,
            )
        }

        OnboardingStep.ExperienceSelection -> {
            OnboardingExperienceStep(
                options = state.experienceOptions,
                selectedOption = state.selectedExperienceIndex,
                onOptionChange = { onIntent(OnboardingIntent.ExperienceChange(it)) },
                modifier = modifier,
            )
        }

        OnboardingStep.RegisterDone -> {
            OnboardingRegisterDoneStep(modifier = modifier)
        }
    }
}

private fun OnboardingStep.toProgressStep(): Int =
    when (this) {
        OnboardingStep.Naming -> PROGRESS_STEP_NAMING

        OnboardingStep.JobSelection -> PROGRESS_STEP_JOB

        OnboardingStep.ExperienceSelection,
        OnboardingStep.RegisterDone,
        -> PROGRESS_STEP_EXPERIENCE
    }
