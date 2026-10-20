package com.dminus14.app.feature.onboarding

import android.content.Context
import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.model.Portfolio
import com.dminus14.app.domain.model.PortfolioDeleteResult
import com.dminus14.app.domain.model.PortfolioOverview
import com.dminus14.app.domain.model.PortfolioStatus
import com.dminus14.app.domain.usecase.DeletePortfolioUseCase
import com.dminus14.app.domain.usecase.GetPortfolioOverviewUseCase
import com.dminus14.app.domain.usecase.GetPortfolioStatusUseCase
import com.dminus14.app.domain.usecase.UploadPortfolioUseCase
import com.dminus14.app.feature.onboarding.handler.OnBoardingPortfolioHandler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import com.dminus14.app.feature.onboarding.OnBoardingInterviewIntent.Portfolio as PortfolioIntent

@OptIn(ExperimentalCoroutinesApi::class)
class OnBoardingPortfolioHandlerTest {
    @Test
    fun `연속 삭제는 한 번만 요청하고 삭제 이전 조회 결과는 삭제 상태를 덮지 않는다`() =
        runTest {
            val delayedOverview = CompletableDeferred<Result<PortfolioOverview>>()
            val getOverview = mockk<GetPortfolioOverviewUseCase>()
            val deletePortfolio = mockk<DeletePortfolioUseCase>()
            val overview =
                PortfolioOverview(
                    Portfolio(
                        portfolioId = "synthetic-id",
                        fileName = "sample.pdf",
                        fileSize = 1,
                        pageCount = 1,
                        status = PortfolioStatus.READY,
                        uploadedAt = null,
                        isInterviewInProgress = false,
                    ),
                    true,
                    null,
                    true,
                    null,
                )
            var loads = 0
            coEvery { getOverview() } coAnswers {
                if (loads++ == 0) Result.success(overview) else delayedOverview.await()
            }
            val deletionResult = Result.success(PortfolioDeleteResult("synthetic-id", ""))
            coEvery { deletePortfolio(any()) } returns deletionResult
            val context = TestContext(this)
            val handler =
                OnBoardingPortfolioHandler(
                    context,
                    mockk<Context>(),
                    getOverview,
                    mockk<UploadPortfolioUseCase>(),
                    mockk<GetPortfolioStatusUseCase>(),
                    deletePortfolio,
                    StandardTestDispatcher(testScheduler),
                    {},
                )
            handler.loadOverview()
            runCurrent()
            handler.loadOverview()
            runCurrent()
            repeat(2) { handler.removePortfolio() }
            runCurrent()
            coVerify(exactly = 1) { deletePortfolio("synthetic-id") }
            delayedOverview.complete(Result.success(overview))
            runCurrent()
            assertNull(handler.readyPortfolioId)
            assertNull(context.state.value.portfolioFileName)
        }

    @Test
    fun `처리 중 새 파일 선택은 전송하지 않고 선택 캐시를 정리한다`() =
        runTest {
            val upload = mockk<UploadPortfolioUseCase>()
            val context = TestContext(this)
            context.state.value = context.state.value.copy(isPortfolioProcessing = true)
            val handler =
                OnBoardingPortfolioHandler(
                    context,
                    mockk<Context>(),
                    mockk<GetPortfolioOverviewUseCase>(),
                    upload,
                    mockk<GetPortfolioStatusUseCase>(),
                    mockk<DeletePortfolioUseCase>(),
                    StandardTestDispatcher(testScheduler),
                    {},
                )
            val file = File.createTempFile("synthetic-portfolio", ".pdf")
            try {
                handler.startPortfolioUpload(
                    PortfolioIntent.PortfolioFileSelected(file, "sample.pdf"),
                )
                runCurrent()
                coVerify(exactly = 0) { upload(any(), any(), any()) }
                assertFalse(file.exists())
            } finally {
                file.delete()
            }
        }

    private class TestContext(
        private val scope: CoroutineScope,
    ) : MviContext<OnBoardingInterviewState, OnBoardingMutation, OnBoardingInterviewEffect> {
        override val state = MutableStateFlow(OnBoardingInterviewState())

        override fun dispatch(mutation: OnBoardingMutation) {
            state.value = OnBoardingReducer.reduce(state.value, mutation)
        }

        override fun sendEffect(effect: OnBoardingInterviewEffect) = Unit

        override fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
    }
}
