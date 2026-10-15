package com.dminus14.app.feature.mypage

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.exception.UnknownException
import com.dminus14.app.domain.model.PortfolioStatus
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.GetInterviewReportListUseCase
import com.dminus14.app.domain.usecase.GetPortfolioOverviewUseCase
import com.dminus14.app.feature.mypage.portfolio.PortfolioFileReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.async

/** 초기 조회를 병렬로 실행하고 포트폴리오 처리 재개를 화면에 알린다. */
internal class MyPageLoadHandler(
    private val context: MviContext<MyPageState, MyPageMutation, MyPageEffect>,
    private val checkUserProfileUseCase: CheckUserProfileUseCase,
    private val getPortfolioOverviewUseCase: GetPortfolioOverviewUseCase,
    private val getInterviewReportListUseCase: GetInterviewReportListUseCase,
    private val portfolioFileReader: PortfolioFileReader,
    private val onEvent: (MyPageLoadEvent) -> Unit,
) {
    private var loadJob: Job? = null
    private var loadVersion = 0

    fun load() {
        loadJob?.cancel()
        val version = ++loadVersion
        loadJob =
            context.launch {
                portfolioFileReader.clearCache()
                context.dispatch(MyPageMutation.LoadStarted)

                val profileDeferred =
                    async {
                        val result = checkUserProfileUseCase()
                        result.rethrowMyPageCancellation()
                    }
                val overviewDeferred =
                    async {
                        val result = getPortfolioOverviewUseCase()
                        result.rethrowMyPageCancellation()
                    }
                val reportsDeferred =
                    async {
                        getInterviewReportListUseCase().rethrowMyPageCancellation()
                    }

                val profileResult = profileDeferred.await()
                val overviewResult = overviewDeferred.await()
                val reportsResult = reportsDeferred.await()

                if (version != loadVersion) return@launch
                context.dispatch(MyPageMutation.LoadFinished)

                profileResult.getOrNull()?.let { profile ->
                    context.dispatch(MyPageMutation.ProfileLoaded(profile))
                }

                overviewResult.getOrNull()?.let { overview ->
                    context.dispatch(MyPageMutation.OverviewLoaded(overview))
                    val portfolio = overview.portfolio
                    if (portfolio != null && portfolio.status == PortfolioStatus.PROCESSING) {
                        val event =
                            MyPageLoadEvent.ProcessingPortfolio(
                                portfolio.portfolioId,
                                portfolio.fileName,
                            )
                        onEvent(event)
                    }
                }

                reportsResult.getOrNull()?.let { reportList ->
                    context.dispatch(MyPageMutation.ReportsLoaded(reportList))
                }

                val failures =
                    listOfNotNull(
                        profileResult.exceptionOrNull(),
                        overviewResult.exceptionOrNull(),
                        reportsResult.exceptionOrNull(),
                    )
                if (failures.isNotEmpty()) handleLoadFailures(failures)
            }
    }

    private fun handleLoadFailures(failures: List<Throwable>) {
        val globalEventError =
            failures.firstOrNull { error ->
                error is NetworkUnavailableException || error is ServerException ||
                    error is UnknownException
            }
        if (globalEventError != null) {
            context.routeMyPageGlobalError(globalEventError)
            return
        }
        val message =
            if (failures.size == 1) {
                failures.first().message?.takeIf { it.isNotBlank() }
                    ?: "일부 정보를 불러오지 못했어요."
            } else {
                "일부 정보를 불러오지 못했어요."
            }
        context.sendEffect(MyPageEffect.ShowToast(message))
    }
}

/** 초기 조회에서 실행이 이어져야 하는 작업만 ViewModel로 전달한다. */
internal sealed interface MyPageLoadEvent {
    data class ProcessingPortfolio(
        val id: String,
        val fileName: String,
    ) : MyPageLoadEvent
}
