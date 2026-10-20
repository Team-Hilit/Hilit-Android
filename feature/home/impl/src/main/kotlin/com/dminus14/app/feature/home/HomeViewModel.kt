package com.dminus14.app.feature.home

import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.exception.UserNotFoundException
import com.dminus14.app.domain.usecase.AbandonInterviewUseCase
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.GetInterviewElapsedTimeUseCase
import com.dminus14.app.domain.usecase.GetInterviewProgressUseCase
import com.dminus14.app.domain.usecase.GetInterviewReportListUseCase
import com.dminus14.app.domain.usecase.GetInterviewResumeUseCase
import com.dminus14.app.domain.usecase.RetainInterviewSessionForCleanupUseCase
import com.dminus14.app.feature.home.mapper.toHomeReportItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    @Suppress("LongParameterList")
    constructor(
        private val checkUserProfileUseCase: CheckUserProfileUseCase,
        private val getInterviewReportListUseCase: GetInterviewReportListUseCase,
        getInterviewResumeUseCase: GetInterviewResumeUseCase,
        getInterviewProgressUseCase: GetInterviewProgressUseCase,
        getInterviewElapsedTimeUseCase: GetInterviewElapsedTimeUseCase,
        abandonInterviewUseCase: AbandonInterviewUseCase,
        retainInterviewSessionForCleanupUseCase: RetainInterviewSessionForCleanupUseCase,
    ) : MviViewModel<HomeIntent, HomeState, HomeEffect>(HomeState()) {
        private val context = createContext(HomeReducer)
        private val session =
            HomeSessionHandler(
                context,
                getInterviewResumeUseCase,
                getInterviewProgressUseCase,
                getInterviewElapsedTimeUseCase,
                abandonInterviewUseCase,
                retainInterviewSessionForCleanupUseCase,
            )
        private var loadJob: Job? = null

        override fun onIntent(intent: HomeIntent) {
            when (intent) {
                HomeIntent.Load -> {
                    load()
                }

                HomeIntent.ClickMyPage -> {
                    context.sendEffect(HomeEffect.GoToMyPageRequested)
                }

                is HomeIntent.ClickReportExpand -> {
                    context.dispatch(
                        HomeMutation.ReportToggled(intent.reportId),
                    )
                }

                is HomeIntent.ClickReportOpen -> {
                    context.sendEffect(
                        HomeEffect.GoToReportRequested(intent.reportId),
                    )
                }

                HomeIntent.ReportSheetCollapsed -> {
                    session.checkSession()
                }

                HomeIntent.ClickSessionStart -> {
                    session.startSession()
                }

                HomeIntent.ClickSessionOverlayDismiss -> {
                    session.dismissOverlay()
                }

                HomeIntent.ClickSessionResume -> {
                    session.resumeSession()
                }
            }
        }

        private fun load() {
            loadJob?.cancel()
            context.dispatch(HomeMutation.LoadStarted)
            loadJob =
                context.launch {
                    if (!loadProfile()) return@launch
                    // 목록 조회 실패는 기존 홈 계약대로 빈 목록으로 표시한다.
                    val reports =
                        getInterviewReportListUseCase()
                            .rethrowHomeCancellation()
                            .getOrNull()
                            ?.reports
                            ?.map { it.toHomeReportItem() }
                            .orEmpty()
                    context.dispatch(HomeMutation.ReportsLoaded(reports))
                }
        }

        private suspend fun loadProfile(): Boolean {
            val profile =
                checkUserProfileUseCase().rethrowHomeCancellation().getOrElse { error ->
                    if (error is UserNotFoundException) {
                        context.dispatch(HomeMutation.LoadStopped)
                        context.sendEffect(HomeEffect.UserNotFound)
                    } else {
                        context.handleHomeFailure(error)
                    }
                    return false
                }
            val name = profile.name?.takeIf { it.isNotBlank() }
            return if (name == null) {
                context.dispatch(HomeMutation.LoadStopped)
                context.sendEffect(HomeEffect.UserNameNotRegistered)
                false
            } else {
                context.dispatch(HomeMutation.ProfileLoaded(name, profile.remainingTicketCount))
                true
            }
        }
    }
