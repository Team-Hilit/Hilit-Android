package com.dminus14.app.feature.login.onboarding

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.exception.InvalidJobRoleException
import com.dminus14.app.domain.exception.NameAlreadyTakenException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.exception.ValidationException
import com.dminus14.app.domain.model.Job
import com.dminus14.app.domain.model.UserProfileUpdate
import com.dminus14.app.domain.usecase.GetJobListUseCase
import com.dminus14.app.domain.usecase.UpdateUserProfileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val getJobList: GetJobListUseCase,
        private val updateUserProfile: UpdateUserProfileUseCase,
    ) : MviViewModel<OnboardingIntent, OnboardingState, OnboardingEffect>(OnboardingState()) {
        private val mvi = createContext(OnboardingReducer)

        override fun onIntent(intent: OnboardingIntent) {
            when (intent) {
                OnboardingIntent.Load -> {
                    mvi.dispatch(OnboardingMutation.ExperienceOptionsInitialized)
                    loadJobs()
                }

                OnboardingIntent.CloseClick -> {
                    mvi.sendEffect(OnboardingEffect.CloseRequested)
                }

                OnboardingIntent.ContinueClick -> {
                    onContinueClick()
                }

                OnboardingIntent.PreviousClick -> {
                    onPreviousClick()
                }

                is OnboardingIntent.NameChange -> {
                    mvi.dispatch(OnboardingMutation.NameEdited(intent.value))
                }

                is OnboardingIntent.JobClick -> {
                    mvi.dispatch(OnboardingMutation.JobSelected(intent.index))
                }

                is OnboardingIntent.ExperienceChange -> {
                    mvi.dispatch(OnboardingMutation.ExperienceSelected(intent.index))
                }
            }
        }

        private fun loadJobs() {
            mvi.launch {
                getJobList()
                    .onSuccess { jobs ->
                        mvi.dispatch(OnboardingMutation.JobsLoaded(jobs))
                    }.onFailure { error ->
                        handleCommonError(error)
                    }
            }
        }

        private fun onContinueClick() {
            val current = state.value
            if (current.step != OnboardingStep.RegisterDone && !current.isContinueEnabled) {
                return
            }
            if (current.isSubmitting) {
                return
            }

            when (current.step) {
                OnboardingStep.Naming -> {
                    mvi.dispatch(OnboardingMutation.StepChanged(OnboardingStep.JobSelection))
                }

                OnboardingStep.JobSelection -> {
                    mvi.dispatch(OnboardingMutation.StepChanged(OnboardingStep.ExperienceSelection))
                }

                OnboardingStep.ExperienceSelection -> {
                    mvi.dispatch(OnboardingMutation.RegistrationReviewEntered)
                }

                OnboardingStep.RegisterDone -> {
                    submitProfile()
                }
            }
        }

        private fun onPreviousClick() {
            when (state.value.step) {
                OnboardingStep.Naming -> {
                    mvi.sendEffect(OnboardingEffect.CloseRequested)
                }

                OnboardingStep.JobSelection -> {
                    mvi.dispatch(OnboardingMutation.StepChanged(OnboardingStep.Naming))
                }

                OnboardingStep.ExperienceSelection -> {
                    mvi.dispatch(OnboardingMutation.StepChanged(OnboardingStep.JobSelection))
                }

                OnboardingStep.RegisterDone -> {
                    mvi.dispatch(OnboardingMutation.StepChanged(OnboardingStep.ExperienceSelection))
                }
            }
        }

        private fun submitProfile() {
            val current = state.value
            val selectedJob =
                current.selectedJobIndex?.let { current.jobs.getOrNull(it) } ?: return
            val careerYears = current.selectedExperienceIndex

            mvi.dispatch(OnboardingMutation.SubmissionStarted)
            mvi.launch {
                updateUserProfile(
                    UserProfileUpdate(
                        name = current.name,
                        jobRole = selectedJob.jobRole,
                        careerYears = careerYears,
                    ),
                ).onSuccess {
                    mvi.dispatch(OnboardingMutation.SubmissionFinished)
                    mvi.sendEffect(OnboardingEffect.Completed)
                }.onFailure { error ->
                    when (error) {
                        is NameAlreadyTakenException -> {
                            mvi.dispatch(OnboardingMutation.NameRejected(error.message))
                        }

                        is InvalidJobRoleException -> {
                            mvi.dispatch(OnboardingMutation.JobRejected(error.message))
                        }

                        is ValidationException -> {
                            mvi.dispatch(OnboardingMutation.ProfileRejected(error.message))
                        }

                        else -> {
                            handleCommonError(error)
                        }
                    }
                }
            }
        }

        // 아래 에러 처리 사항은 임시입니다. 공통 처리 기획자 문의 모든 ViewModel 일괄 수정 예정
        private suspend fun handleCommonError(error: Throwable) {
            mvi.dispatch(OnboardingMutation.SubmissionFinished)
            when (error) {
                is NetworkUnavailableException -> {
                    GlobalErrorHandler.emit(GlobalAppEvent.ShowNetworkErrorAndExit)
                }

                is ServerException -> {
                    GlobalErrorHandler.emit(GlobalAppEvent.ShowServerErrorAndExit)
                }

                else -> {
                    GlobalErrorHandler.emit(GlobalAppEvent.ShowUnknownError)
                }
            }
        }
    }

private sealed interface OnboardingMutation {
    data object ExperienceOptionsInitialized : OnboardingMutation

    data class NameEdited(
        val value: String,
    ) : OnboardingMutation

    data class JobSelected(
        val index: Int,
    ) : OnboardingMutation

    data class ExperienceSelected(
        val index: Int,
    ) : OnboardingMutation

    data class JobsLoaded(
        val jobs: List<Job>,
    ) : OnboardingMutation

    data class StepChanged(
        val step: OnboardingStep,
    ) : OnboardingMutation

    data object RegistrationReviewEntered : OnboardingMutation

    data object SubmissionStarted : OnboardingMutation

    data object SubmissionFinished : OnboardingMutation

    data class NameRejected(
        val message: String?,
    ) : OnboardingMutation

    data class JobRejected(
        val message: String?,
    ) : OnboardingMutation

    data class ProfileRejected(
        val message: String?,
    ) : OnboardingMutation
}

/** 사용자 입력과 비동기 결과를 화면 상태로만 계산한다. */
private object OnboardingReducer : MviReducer<OnboardingState, OnboardingMutation> {
    override fun reduce(
        state: OnboardingState,
        mutation: OnboardingMutation,
    ): OnboardingState =
        with(state) {
            when (mutation) {
                OnboardingMutation.ExperienceOptionsInitialized -> {
                    copy(
                        experienceOptions = DefaultExperienceOptions,
                        selectedExperienceIndex = 0,
                        isContinueEnabled = resolveContinueEnabled(this),
                    )
                }

                is OnboardingMutation.NameEdited -> {
                    copy(
                        name = sanitizeNickname(mutation.value),
                        errorMessage = null,
                    ).withContinueEnabled()
                }

                is OnboardingMutation.JobSelected -> {
                    copy(
                        selectedJobIndex = mutation.index,
                        errorMessage = null,
                    ).withContinueEnabled()
                }

                is OnboardingMutation.ExperienceSelected -> {
                    copy(selectedExperienceIndex = mutation.index).withContinueEnabled()
                }

                is OnboardingMutation.JobsLoaded -> {
                    copy(jobs = mutation.jobs).withContinueEnabled()
                }

                is OnboardingMutation.StepChanged -> {
                    copy(step = mutation.step).withContinueEnabled()
                }

                OnboardingMutation.RegistrationReviewEntered -> {
                    copy(step = OnboardingStep.RegisterDone, isContinueEnabled = true)
                }

                OnboardingMutation.SubmissionStarted -> {
                    copy(isSubmitting = true, errorMessage = null)
                }

                OnboardingMutation.SubmissionFinished -> {
                    copy(isSubmitting = false)
                }

                is OnboardingMutation.NameRejected -> {
                    copy(
                        step = OnboardingStep.Naming,
                        isSubmitting = false,
                        errorMessage = mutation.message,
                    ).withContinueEnabled()
                }

                is OnboardingMutation.JobRejected -> {
                    copy(
                        step = OnboardingStep.JobSelection,
                        isSubmitting = false,
                        errorMessage = mutation.message,
                    ).withContinueEnabled()
                }

                is OnboardingMutation.ProfileRejected -> {
                    copy(isSubmitting = false, errorMessage = mutation.message)
                }
            }
        }

    private fun OnboardingState.withContinueEnabled(): OnboardingState =
        copy(isContinueEnabled = resolveContinueEnabled(this))

    private fun resolveContinueEnabled(state: OnboardingState): Boolean =
        when (state.step) {
            OnboardingStep.Naming -> {
                isValidNickname(state.name)
            }

            OnboardingStep.JobSelection -> {
                state.selectedJobIndex != null &&
                    state.selectedJobIndex in state.jobs.indices
            }

            OnboardingStep.ExperienceSelection -> {
                state.selectedExperienceIndex in state.experienceOptions.indices
            }

            OnboardingStep.RegisterDone -> {
                true
            }
        }

    private fun sanitizeNickname(input: String): String =
        input
            .filter { char -> NICKNAME_ALLOWED_CHAR_REGEX.matches(char.toString()) }
            .take(NICKNAME_MAX_LENGTH)

    private fun isValidNickname(name: String): Boolean =
        sanitizeNickname(name) == name && name.isNotBlank()

    private const val NICKNAME_MAX_LENGTH = 5

    // ㆍ(U+318D, Hangul Letter Araea): 천지인 키보드의 "천" 점 입력.
    // 조합이 완성되기 전 IME가 원시 문자를 그대로 흘려보내는 경우가 있어 허용한다.
    private val NICKNAME_ALLOWED_CHAR_REGEX = Regex("[a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣㆍ]")
}
