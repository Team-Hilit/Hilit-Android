package com.dminus14.app.feature.interview.error

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.usecase.AbandonInterviewUseCase
import com.dminus14.app.domain.usecase.ConfirmInterviewResumeUseCase
import com.dminus14.app.domain.usecase.GetInterviewMediaManifestUseCase
import com.dminus14.app.domain.usecase.GetInterviewProgressUseCase
import com.dminus14.app.domain.usecase.GetInterviewResumeUseCase
import com.dminus14.app.domain.usecase.RetainInterviewSessionForCleanupUseCase
import com.dminus14.app.domain.usecase.SavePendingInterviewAnswerUseCase
import com.dminus14.app.domain.usecase.SubmitAnswerUseCase
import com.dminus14.app.feature.interview.error.handler.InterviewErrorAbortHandler
import com.dminus14.app.feature.interview.error.handler.InterviewErrorResumeHandler
import com.dminus14.app.feature.interview.interview.InterviewRecoveryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@HiltViewModel
@Suppress("LongParameterList")
class InterviewErrorViewModel
    @Inject
    constructor(
        getProgress: GetInterviewProgressUseCase,
        getManifest: GetInterviewMediaManifestUseCase,
        getResume: GetInterviewResumeUseCase,
        confirmResume: ConfirmInterviewResumeUseCase,
        abandonInterview: AbandonInterviewUseCase,
        submitAnswer: SubmitAnswerUseCase,
        savePendingAnswer: SavePendingInterviewAnswerUseCase,
        retainSessionForCleanup: RetainInterviewSessionForCleanupUseCase,
        recoveryStore: InterviewRecoveryStore,
    ) : MviViewModel<InterviewErrorIntent, InterviewErrorState, InterviewErrorEffect>(
            InterviewErrorState(),
        ) {
        private val context = createContext(InterviewErrorReducer)
        private val operation = InterviewErrorOperation()
        private val abortHandler =
            InterviewErrorAbortHandler(
                context = context,
                abandonInterview = abandonInterview,
                retainSessionForCleanup = retainSessionForCleanup,
                recoveryStore = recoveryStore,
                operation = operation,
                onFailure = ::handleFailure,
            )
        private val resumeHandler =
            InterviewErrorResumeHandler(
                context = context,
                getProgress = getProgress,
                getManifest = getManifest,
                getResume = getResume,
                confirmResume = confirmResume,
                submitAnswer = submitAnswer,
                savePendingAnswer = savePendingAnswer,
                recoveryStore = recoveryStore,
                operation = operation,
                onGlobalError = ::emitGlobal,
                onFailure = ::handleFailure,
                onRetain = abortHandler::retainAndFinish,
            )

        override fun onIntent(intent: InterviewErrorIntent) {
            when (intent) {
                is InterviewErrorIntent.Load -> {
                    resumeHandler.load(intent.errorType)
                }

                InterviewErrorIntent.ClickAbort -> {
                    if (!operation.isRunning && !operation.isCompleted) {
                        resumeHandler.cancelLoad()
                        abortHandler.abort(resumeHandler.sessionId)
                    }
                }

                InterviewErrorIntent.ClickResume -> {
                    resumeHandler.resume()
                }
            }
        }

        private fun handleFailure(error: Throwable) {
            when (error) {
                is CancellationException -> {
                    throw error
                }

                is NetworkUnavailableException -> {
                    context.dispatch(
                        InterviewErrorMutation.NetworkRetryRequired,
                    )
                }

                is ServerException -> {
                    emitGlobal(GlobalAppEvent.ShowServerErrorAndExit)
                }

                else -> {
                    emitGlobal(GlobalAppEvent.ShowUnknownError)
                }
            }
        }

        private fun emitGlobal(event: GlobalAppEvent) {
            if (event == GlobalAppEvent.ShowUnknownError) {
                context.dispatch(InterviewErrorMutation.UnknownErrorReported)
            }
            context.launch { GlobalErrorHandler.emit(event) }
        }
    }
