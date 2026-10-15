package com.dminus14.app.feature.onboarding

import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.FREETEXT_MAX_LENGTH
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.JD_TEXT_MAX_LENGTH
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_FREETEXT_NOT_RELEVANT
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_FREETEXT_TOO_SHORT
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_LINK_FORMAT
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_PORTFOLIO_REQUIRED
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_PORTFOLIO_SYSTEM_FAILED
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.MESSAGE_TEXT_TOO_SHORT
import com.dminus14.app.feature.onboarding.OnBoardingPolicy.PORTFOLIO_PROGRESS_COMPLETE
import com.dminus14.app.feature.onboarding.OnBoardingMutation.Flow as FlowMutation
import com.dminus14.app.feature.onboarding.OnBoardingMutation.JobDescription as JdMutation
import com.dminus14.app.feature.onboarding.OnBoardingMutation.Portfolio as PortfolioMutation
import com.dminus14.app.feature.onboarding.OnBoardingMutation.SessionPreparation as SessionMutation

internal sealed interface OnBoardingMutation {
    sealed interface Flow : OnBoardingMutation {
        data class MainProjectChanged(
            val value: String,
        ) : Flow

        data class ErrorReported(
            val message: String?,
        ) : Flow

        data object MainProjectTooShort : Flow

        data class StepAdvanced(
            val next: OnBoardingInterviewStep,
        ) : Flow

        data class StepReturned(
            val previous: OnBoardingInterviewStep,
        ) : Flow
    }

    sealed interface JobDescription : OnBoardingMutation {
        data class JdTabChanged(
            val tab: JobDescriptionTab,
            val cancelled: Boolean,
        ) : JobDescription

        data class JdTextChanged(
            val value: String,
        ) : JobDescription

        data class JdLinkEdited(
            val value: String,
        ) : JobDescription

        data class JdLinkFormatRejected(
            val value: String,
        ) : JobDescription

        data object JdValidationStarted : JobDescription

        data object JdValidationSucceeded : JobDescription

        data class JdValidationRejected(
            val message: String,
        ) : JobDescription

        data object JdValidationInterrupted : JobDescription

        data object JdTextTooShort : JobDescription

        data object JdFormatRejected : JobDescription
    }

    sealed interface Portfolio : OnBoardingMutation {
        data object PortfolioNoticeDismissed : Portfolio

        data class ExistingPortfolioLoaded(
            val fileName: String,
        ) : Portfolio

        data object PortfolioRequired : Portfolio

        data object PortfolioDeletionStarted : Portfolio

        data object PortfolioDeleted : Portfolio

        data class PortfolioDeletionFailed(
            val message: String?,
        ) : Portfolio

        data class PortfolioUploadStarted(
            val fileName: String,
        ) : Portfolio

        data class PortfolioFileRejected(
            val message: String,
        ) : Portfolio

        data class PortfolioPollingFailed(
            val message: String,
        ) : Portfolio

        data object PortfolioReady : Portfolio

        data class PortfolioPollingProgressed(
            val progress: Int,
        ) : Portfolio

        data class PortfolioNoticeShown(
            val phase: ExistingPortfolioModalPhase,
            val deleteAvailable: Boolean,
        ) : Portfolio
    }

    sealed interface SessionPreparation : OnBoardingMutation {
        data object RelevancePortfolioRetrySelected : SessionPreparation

        data object RelevanceBypassed : SessionPreparation

        data object PreloadFailureAcknowledged : SessionPreparation

        data object ProfileMissing : SessionPreparation

        data object SessionCreationStarted : SessionPreparation

        data object SessionCreated : SessionPreparation

        data object AccountSuspended : SessionPreparation

        data class RelevanceRejected(
            val count: Int,
            val showEscape: Boolean,
        ) : SessionPreparation

        data class SessionCreationFailed(
            val message: String?,
        ) : SessionPreparation

        data object SessionReady : SessionPreparation

        data object SessionFailed : SessionPreparation

        data object SessionTimedOut : SessionPreparation
    }
}

internal object OnBoardingReducer : MviReducer<OnBoardingInterviewState, OnBoardingMutation> {
    override fun reduce(
        state: OnBoardingInterviewState,
        mutation: OnBoardingMutation,
    ): OnBoardingInterviewState =
        when (mutation) {
            is FlowMutation -> OnBoardingFlowReducer.reduce(state, mutation)
            is JdMutation -> OnBoardingJobDescriptionReducer.reduce(state, mutation)
            is PortfolioMutation -> OnBoardingPortfolioReducer.reduce(state, mutation)
            is SessionMutation -> OnBoardingSessionPreparationReducer.reduce(state, mutation)
        }
}

private object OnBoardingFlowReducer : MviReducer<OnBoardingInterviewState, FlowMutation> {
    override fun reduce(
        state: OnBoardingInterviewState,
        mutation: FlowMutation,
    ): OnBoardingInterviewState =
        with(state) {
            when (mutation) {
                is FlowMutation.MainProjectChanged -> {
                    copy(
                        mainProjectText = mutation.value.take(FREETEXT_MAX_LENGTH),
                        mainProjectError = null,
                    )
                }

                is FlowMutation.ErrorReported -> {
                    copy(errorMessage = mutation.message)
                }

                FlowMutation.MainProjectTooShort -> {
                    copy(mainProjectError = MESSAGE_FREETEXT_TOO_SHORT)
                }

                is FlowMutation.StepAdvanced -> {
                    reduceStepAdvanced(state, mutation)
                }

                is FlowMutation.StepReturned -> {
                    copy(step = mutation.previous, errorMessage = null)
                }
            }
        }

    private fun reduceStepAdvanced(
        state: OnBoardingInterviewState,
        mutation: FlowMutation.StepAdvanced,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                step = mutation.next,
                errorMessage = null,
                jdLinkStatus =
                    if (jdLinkStatus ==
                        JdLinkStatus.Validating
                    ) {
                        JdLinkStatus.Idle
                    } else {
                        jdLinkStatus
                    },
            )
        }
}

private object OnBoardingJobDescriptionReducer :
    MviReducer<OnBoardingInterviewState, JdMutation> {
    override fun reduce(
        state: OnBoardingInterviewState,
        mutation: JdMutation,
    ): OnBoardingInterviewState =
        with(state) {
            when (mutation) {
                is JdMutation.JdTabChanged -> {
                    reduceJdTabChanged(state, mutation)
                }

                is JdMutation.JdTextChanged -> {
                    copy(
                        jobDescriptionText = mutation.value.take(JD_TEXT_MAX_LENGTH),
                        jdTextError = null,
                    )
                }

                is JdMutation.JdLinkEdited -> {
                    reduceJdLinkEdited(state, mutation)
                }

                is JdMutation.JdLinkFormatRejected -> {
                    reduceJdLinkFormatRejected(state, mutation)
                }

                JdMutation.JdValidationStarted -> {
                    copy(jdLinkStatus = JdLinkStatus.Validating, jdLinkSubText = "")
                }

                JdMutation.JdValidationSucceeded -> {
                    copy(
                        jdLinkStatus = JdLinkStatus.Valid,
                        jdLinkSubText = "",
                    )
                }

                is JdMutation.JdValidationRejected -> {
                    copy(
                        jdLinkStatus = JdLinkStatus.Invalid,
                        jdLinkSubText = mutation.message,
                    )
                }

                JdMutation.JdValidationInterrupted -> {
                    copy(jdLinkStatus = JdLinkStatus.Idle)
                }

                JdMutation.JdTextTooShort -> {
                    copy(jdTextError = MESSAGE_TEXT_TOO_SHORT)
                }

                JdMutation.JdFormatRejected -> {
                    copy(
                        jdLinkStatus = JdLinkStatus.Invalid,
                        jdLinkSubText = MESSAGE_LINK_FORMAT,
                    )
                }
            }
        }

    private fun reduceJdTabChanged(
        state: OnBoardingInterviewState,
        mutation: JdMutation.JdTabChanged,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                jobDescriptionTab = mutation.tab,
                jdLinkStatus =
                    if (mutation.cancelled) JdLinkStatus.Idle else jdLinkStatus,
                jdLinkSubText =
                    if (mutation.cancelled) "" else jdLinkSubText,
            )
        }

    private fun reduceJdLinkEdited(
        state: OnBoardingInterviewState,
        mutation: JdMutation.JdLinkEdited,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                jobDescriptionLink = mutation.value,
                jdLinkStatus = JdLinkStatus.Idle,
                jdLinkSubText = "",
            )
        }

    private fun reduceJdLinkFormatRejected(
        state: OnBoardingInterviewState,
        mutation: JdMutation.JdLinkFormatRejected,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                jobDescriptionLink = mutation.value,
                jdLinkStatus = JdLinkStatus.Invalid,
                jdLinkSubText = MESSAGE_LINK_FORMAT,
            )
        }
}

private object OnBoardingPortfolioReducer :
    MviReducer<OnBoardingInterviewState, PortfolioMutation> {
    override fun reduce(
        state: OnBoardingInterviewState,
        mutation: PortfolioMutation,
    ): OnBoardingInterviewState =
        with(state) {
            when (mutation) {
                PortfolioMutation.PortfolioNoticeDismissed -> {
                    copy(existingPortfolioModalPhase = ExistingPortfolioModalPhase.None)
                }

                is PortfolioMutation.ExistingPortfolioLoaded -> {
                    copy(
                        portfolioFileName = mutation.fileName,
                        portfolioUploadProgress = PORTFOLIO_PROGRESS_COMPLETE,
                    )
                }

                PortfolioMutation.PortfolioRequired -> {
                    copy(portfolioErrorMessage = MESSAGE_PORTFOLIO_REQUIRED)
                }

                PortfolioMutation.PortfolioDeletionStarted -> {
                    copy(isPortfolioProcessing = true, portfolioErrorMessage = null)
                }

                PortfolioMutation.PortfolioDeleted -> {
                    reducePortfolioDeleted(state)
                }

                is PortfolioMutation.PortfolioDeletionFailed -> {
                    copy(
                        isPortfolioProcessing = false,
                        portfolioErrorMessage = mutation.message,
                    )
                }

                is PortfolioMutation.PortfolioUploadStarted -> {
                    reducePortfolioUploadStarted(state, mutation)
                }

                is PortfolioMutation.PortfolioFileRejected -> {
                    reducePortfolioFileRejected(state, mutation)
                }

                is PortfolioMutation.PortfolioPollingFailed -> {
                    reducePortfolioPollingFailed(state, mutation)
                }

                PortfolioMutation.PortfolioReady -> {
                    copy(
                        isPortfolioProcessing = false,
                        portfolioUploadProgress = PORTFOLIO_PROGRESS_COMPLETE,
                    )
                }

                is PortfolioMutation.PortfolioPollingProgressed -> {
                    copy(portfolioUploadProgress = mutation.progress)
                }

                is PortfolioMutation.PortfolioNoticeShown -> {
                    reducePortfolioNoticeShown(state, mutation)
                }
            }
        }

    private fun reducePortfolioDeleted(state: OnBoardingInterviewState): OnBoardingInterviewState =
        with(state) {
            copy(
                portfolioFileName = null,
                isPortfolioProcessing = false,
                portfolioUploadProgress = 0,
                hasShownExistingPortfolioNotice = false,
                existingPortfolioModalPhase = ExistingPortfolioModalPhase.None,
            )
        }

    private fun reducePortfolioUploadStarted(
        state: OnBoardingInterviewState,
        mutation: PortfolioMutation.PortfolioUploadStarted,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                portfolioFileName = mutation.fileName,
                isPortfolioProcessing = true,
                portfolioErrorMessage = null,
                portfolioUploadProgress = 0,
            )
        }

    private fun reducePortfolioFileRejected(
        state: OnBoardingInterviewState,
        mutation: PortfolioMutation.PortfolioFileRejected,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                isPortfolioProcessing = false,
                portfolioFileName = null,
                portfolioErrorMessage = mutation.message,
                portfolioUploadProgress = 0,
            )
        }

    private fun reducePortfolioPollingFailed(
        state: OnBoardingInterviewState,
        mutation: PortfolioMutation.PortfolioPollingFailed,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                isPortfolioProcessing = false,
                portfolioErrorMessage =
                    mutation.message,
                portfolioUploadProgress = 0,
            )
        }

    private fun reducePortfolioNoticeShown(
        state: OnBoardingInterviewState,
        mutation: PortfolioMutation.PortfolioNoticeShown,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                existingPortfolioModalPhase = mutation.phase,
                hasShownExistingPortfolioNotice = true,
                existingDeleteAvailable = mutation.deleteAvailable,
            )
        }
}

private object OnBoardingSessionPreparationReducer :
    MviReducer<OnBoardingInterviewState, SessionMutation> {
    override fun reduce(
        state: OnBoardingInterviewState,
        mutation: SessionMutation,
    ): OnBoardingInterviewState =
        with(state) {
            when (mutation) {
                SessionMutation.RelevancePortfolioRetrySelected -> {
                    reduceRelevancePortfolioRetrySelected(state)
                }

                SessionMutation.RelevanceBypassed -> {
                    reduceRelevanceBypassed(state)
                }

                SessionMutation.PreloadFailureAcknowledged -> {
                    reducePreloadFailureAcknowledged(state)
                }

                SessionMutation.ProfileMissing -> {
                    copy(errorMessage = "필요한 정보를 불러오지 못했어요. 다시 시도해 주세요.")
                }

                SessionMutation.SessionCreationStarted -> {
                    copy(loadingBasicInfo = OnBoardingLoadingStepStatus.InProgress)
                }

                SessionMutation.SessionCreated -> {
                    reduceSessionCreated(state)
                }

                SessionMutation.AccountSuspended -> {
                    reduceAccountSuspended(state)
                }

                is SessionMutation.RelevanceRejected -> {
                    reduceRelevanceRejected(state, mutation)
                }

                is SessionMutation.SessionCreationFailed -> {
                    reduceSessionCreationFailed(state, mutation)
                }

                SessionMutation.SessionReady -> {
                    reduceSessionReady(state)
                }

                SessionMutation.SessionFailed -> {
                    copy(errorMessage = "면접 준비에 실패했어요. 다시 시도해 주세요.")
                }

                SessionMutation.SessionTimedOut -> {
                    copy(errorMessage = "면접 준비가 지연되고 있어요. 잠시 후 다시 시도해 주세요.")
                }
            }
        }

    private fun reduceRelevancePortfolioRetrySelected(
        state: OnBoardingInterviewState,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                showRelevanceFailDialog = false,
                step = OnBoardingInterviewStep.Portfolio,
                mainProjectRelevanceFailCount = 0,
                mainProjectError = null,
                errorMessage = null,
            )
        }

    private fun reduceRelevanceBypassed(
        state: OnBoardingInterviewState,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                step = OnBoardingInterviewStep.Preload,
                showRelevanceFailDialog = false,
                mainProjectText = "",
                mainProjectError = null,
                mainProjectRelevanceFailCount = 0,
                errorMessage = null,
            )
        }

    private fun reducePreloadFailureAcknowledged(
        state: OnBoardingInterviewState,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                step = OnBoardingInterviewStep.MainProject,
                errorMessage = null,
                loadingBasicInfo = OnBoardingLoadingStepStatus.Waiting,
                loadingJd = OnBoardingLoadingStepStatus.Waiting,
                loadingPortfolio = OnBoardingLoadingStepStatus.Waiting,
            )
        }

    private fun reduceSessionCreated(state: OnBoardingInterviewState): OnBoardingInterviewState =
        with(state) {
            copy(
                loadingBasicInfo = OnBoardingLoadingStepStatus.Completed,
                loadingJd = OnBoardingLoadingStepStatus.InProgress,
                loadingPortfolio = OnBoardingLoadingStepStatus.InProgress,
            )
        }

    private fun reduceAccountSuspended(state: OnBoardingInterviewState): OnBoardingInterviewState =
        with(state) {
            copy(
                loadingBasicInfo = OnBoardingLoadingStepStatus.Waiting,
                loadingJd = OnBoardingLoadingStepStatus.Waiting,
                loadingPortfolio = OnBoardingLoadingStepStatus.Waiting,
            )
        }

    private fun reduceRelevanceRejected(
        state: OnBoardingInterviewState,
        mutation: SessionMutation.RelevanceRejected,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                step = OnBoardingInterviewStep.MainProject,
                showRelevanceFailDialog = mutation.showEscape,
                mainProjectRelevanceFailCount = mutation.count,
                mainProjectError = MESSAGE_FREETEXT_NOT_RELEVANT,
                loadingBasicInfo = OnBoardingLoadingStepStatus.Waiting,
                loadingJd = OnBoardingLoadingStepStatus.Waiting,
                loadingPortfolio = OnBoardingLoadingStepStatus.Waiting,
                errorMessage = null,
            )
        }

    private fun reduceSessionCreationFailed(
        state: OnBoardingInterviewState,
        mutation: SessionMutation.SessionCreationFailed,
    ): OnBoardingInterviewState =
        with(state) {
            copy(
                errorMessage = mutation.message,
                loadingBasicInfo = OnBoardingLoadingStepStatus.Waiting,
                loadingJd = OnBoardingLoadingStepStatus.Waiting,
                loadingPortfolio = OnBoardingLoadingStepStatus.Waiting,
            )
        }

    private fun reduceSessionReady(state: OnBoardingInterviewState): OnBoardingInterviewState =
        with(state) {
            copy(
                loadingBasicInfo = OnBoardingLoadingStepStatus.Completed,
                loadingJd = OnBoardingLoadingStepStatus.Completed,
                loadingPortfolio = OnBoardingLoadingStepStatus.Completed,
            )
        }
}
