package com.dminus14.app.feature.onboarding

import android.content.Context
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.DeletePortfolioUseCase
import com.dminus14.app.domain.usecase.GetInterviewSessionUseCase
import com.dminus14.app.domain.usecase.GetPortfolioOverviewUseCase
import com.dminus14.app.domain.usecase.GetPortfolioStatusUseCase
import com.dminus14.app.domain.usecase.MakeInterviewSessionUseCase
import com.dminus14.app.domain.usecase.SaveInterviewSessionProgressUseCase
import com.dminus14.app.domain.usecase.UploadPortfolioUseCase
import com.dminus14.app.domain.usecase.ValidateJdUrlUseCase
import com.dminus14.app.feature.onboarding.handler.OnBoardingFlowHandler
import com.dminus14.app.feature.onboarding.handler.OnBoardingJobDescriptionHandler
import com.dminus14.app.feature.onboarding.handler.OnBoardingPortfolioHandler
import com.dminus14.app.feature.onboarding.handler.OnBoardingSessionPreparationHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.Flow as FlowIntent
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.JobDescription as JdIntent
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.Portfolio as PortfolioIntent
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.SessionPreparation as SessionIntent
import com.dminus14.app.feature.onboarding.OnBoardingMutation.Flow as FlowMutation
import com.dminus14.app.feature.onboarding.OnBoardingMutation.Portfolio as PortfolioMutation
import com.dminus14.app.feature.onboarding.OnBoardingMutation.SessionPreparation as SessionMutation

@HiltViewModel
class OnBoardingInterviewViewModel
    @Inject
    @Suppress("LongParameterList")
    constructor(
        @ApplicationContext context: Context,
        checkUserProfile: CheckUserProfileUseCase,
        getPortfolioOverview: GetPortfolioOverviewUseCase,
        uploadPortfolio: UploadPortfolioUseCase,
        getPortfolioStatus: GetPortfolioStatusUseCase,
        deletePortfolio: DeletePortfolioUseCase,
        validateJdUrl: ValidateJdUrlUseCase,
        makeInterviewSession: MakeInterviewSessionUseCase,
        getInterviewSession: GetInterviewSessionUseCase,
        saveInterviewSessionProgress: SaveInterviewSessionProgressUseCase,
        @OnBoardingIoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : MviViewModel<
            OnBoardingInterviewIntent,
            OnBoardingInterviewState,
            OnBoardingInterviewEffect,
        >(
            OnBoardingInterviewState(),
        ) {
        private val mvi = createContext(OnBoardingReducer)
        private val session: OnBoardingSessionPreparationHandler =
            OnBoardingSessionPreparationHandler(
                mvi,
                makeInterviewSession,
                getInterviewSession,
                saveInterviewSessionProgress,
            )
        private val portfolio: OnBoardingPortfolioHandler =
            OnBoardingPortfolioHandler(
                mvi,
                context,
                getPortfolioOverview,
                uploadPortfolio,
                getPortfolioStatus,
                deletePortfolio,
                ioDispatcher,
                ::advance,
            )
        private val jobDescription: OnBoardingJobDescriptionHandler =
            OnBoardingJobDescriptionHandler(mvi, validateJdUrl, ::advance)
        private val flow: OnBoardingFlowHandler =
            OnBoardingFlowHandler(mvi, checkUserProfile, portfolio::loadOverview) { next ->
                jobDescription.cancelValidation()
                if (next == OnBoardingInterviewStep.Preload) startPreload()
            }

        override fun onIntent(intent: OnBoardingInterviewIntent) {
            when (intent) {
                is FlowIntent -> handleFlow(intent)
                is JdIntent -> jobDescription.onIntent(intent)
                is PortfolioIntent -> portfolio.onIntent(intent)
                is SessionIntent -> handleSession(intent)
            }
        }

        private fun handleFlow(intent: FlowIntent) {
            when (intent) {
                FlowIntent.Load -> {
                    flow.load()
                }

                FlowIntent.ClickClose -> {
                    mvi.sendEffect(OnBoardingInterviewEffect.CloseRequested)
                }

                FlowIntent.ClickSkip -> {
                    advance()
                }

                FlowIntent.ClickPrevious -> {
                    flow.onPreviousClick()
                }

                FlowIntent.ClickContinue -> {
                    when (state.value.step) {
                        OnBoardingInterviewStep.JobDescription -> {
                            jobDescription.submitJobDescription()
                        }

                        OnBoardingInterviewStep.Portfolio -> {
                            if (portfolio.readyPortfolioId == null) {
                                mvi.dispatch(PortfolioMutation.PortfolioRequired)
                            } else {
                                advance()
                            }
                        }

                        OnBoardingInterviewStep.MainProject -> {
                            flow.submitMainProject()
                        }

                        OnBoardingInterviewStep.Preload -> {
                            Unit
                        }
                    }
                }

                is FlowIntent.MainProjectTextChange -> {
                    mvi.dispatch(FlowMutation.MainProjectChanged(intent.value))
                }
            }
        }

        private fun handleSession(intent: SessionIntent) {
            when (intent) {
                SessionIntent.ClickRelevanceRetryPortfolio -> {
                    mvi.dispatch(SessionMutation.RelevancePortfolioRetrySelected)
                }

                SessionIntent.ClickRelevanceProceedWithoutMainProject -> {
                    mvi.dispatch(SessionMutation.RelevanceBypassed)
                    startPreload()
                }

                SessionIntent.ClickPreloadFailureAcknowledged -> {
                    mvi.dispatch(SessionMutation.PreloadFailureAcknowledged)
                }
            }
        }

        private fun advance() = flow.advanceStep()

        private fun startPreload() =
            session.startPreload(
                flow.jobRole,
                flow.careerYears,
                portfolio.readyPortfolioId,
                jobDescription.jdUrl,
                jobDescription.jdText,
            )
    }
