package com.dminus14.app.feature.onboarding.handler

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.feature.onboarding.OnBoardingInterviewEffect
import com.dminus14.app.feature.onboarding.OnBoardingInterviewState
import com.dminus14.app.feature.onboarding.OnBoardingInterviewStep
import com.dminus14.app.feature.onboarding.OnBoardingMutation
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.FREETEXT_MIN_LENGTH
import kotlinx.coroutines.CancellationException
import com.dminus14.app.feature.onboarding.OnBoardingMutation.Flow as FlowMutation

/** 화면의 진행과 초기 프로필 작업을 소유한다. */
internal class OnBoardingFlowHandler(
    private val context:
        MviContext<OnBoardingInterviewState, OnBoardingMutation, OnBoardingInterviewEffect>,
    private val checkUserProfile: CheckUserProfileUseCase,
    private val onProfileLoaded: () -> Unit,
    private val onAdvance: (OnBoardingInterviewStep) -> Unit,
) {
    var jobRole: String? = null
        private set
    var careerYears: Int? = null
        private set

    fun load() {
        context.launch {
            checkUserProfile()
                .onSuccess { profile ->
                    jobRole = profile.jobRole
                    careerYears = profile.careerYears
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    // 직무 연차 없을 경우 직무 연차 입력 온보딩으로 route할지 질문중
                    context.dispatch(FlowMutation.ErrorReported(error.message))
                }

            onProfileLoaded()
        }
    }

    fun submitMainProject() {
        val content = context.state.value.mainProjectText
        if (content.isNotEmpty() && content.length < FREETEXT_MIN_LENGTH) {
            context.dispatch(FlowMutation.MainProjectTooShort)
            return
        }
        advanceStep()
    }

    fun advanceStep() {
        val next =
            when (context.state.value.step) {
                OnBoardingInterviewStep.JobDescription -> OnBoardingInterviewStep.Portfolio
                OnBoardingInterviewStep.Portfolio -> OnBoardingInterviewStep.MainProject
                OnBoardingInterviewStep.MainProject -> OnBoardingInterviewStep.Preload
                else -> return
            }
        context.dispatch(FlowMutation.StepAdvanced(next))
        onAdvance(next)
    }

    fun onPreviousClick() {
        val previous =
            when (context.state.value.step) {
                OnBoardingInterviewStep.JobDescription -> {
                    context.sendEffect(OnBoardingInterviewEffect.CloseRequested)
                    return
                }

                OnBoardingInterviewStep.Portfolio -> {
                    OnBoardingInterviewStep.JobDescription
                }

                OnBoardingInterviewStep.MainProject -> {
                    OnBoardingInterviewStep.Portfolio
                }

                OnBoardingInterviewStep.Preload -> {
                    // Preload 스텝에선 뒤로가기를 봉쇄한다.
                    return
                }
            }
        context.dispatch(FlowMutation.StepReturned(previous))
    }
}
