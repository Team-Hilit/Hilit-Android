package com.dminus14.app.feature.mypage

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.model.PortfolioDeleteResult
import com.dminus14.app.domain.model.PortfolioOverview
import com.dminus14.app.domain.model.PortfolioStatus
import com.dminus14.app.domain.model.PortfolioUploadResult
import com.dminus14.app.domain.repository.PortfolioRepository
import com.dminus14.app.domain.usecase.DeletePortfolioUseCase
import com.dminus14.app.domain.usecase.GetPortfolioOverviewUseCase
import com.dminus14.app.domain.usecase.GetPortfolioStatusUseCase
import com.dminus14.app.domain.usecase.UploadPortfolioUseCase
import com.dminus14.app.feature.mypage.portfolio.PortfolioFileReader
import com.dminus14.app.feature.mypage.portfolio.PortfolioFileResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MyPageMviTest {
    @Test
    fun `안내할 이용권이나 실패 정보가 없으면 말풍선을 열지 않는다`() {
        val initial = MyPageState()
        assertEquals(initial, MyPageReducer.reduce(initial, MyPageMutation.TicketTooltipToggled))
        assertEquals(initial, MyPageReducer.reduce(initial, MyPageMutation.UploadTooltipToggled))
        val toggled = MyPageReducer.reduce(initial, MyPageMutation.ReportToggled("missing"))
        assertEquals(initial, toggled)
    }

    @Test
    fun `업로드 실패는 다른 섹션과 펼친 리포트 상태를 유지한다`() {
        val initial = MyPageState(remainingTicketCount = 3, expandedReportIds = setOf("report"))
        val failure = MyPageMutation.UploadFailed("synthetic.pdf", "portfolio")
        val actual = MyPageReducer.reduce(initial, failure)
        val expected =
            initial.copy(
                portfolioState = MyPagePortfolioState.Failed("synthetic.pdf", "portfolio"),
            )
        assertEquals(expected, actual)
    }

    @Test
    fun `접수 취소를 연속 요청해도 상태 조회는 한 번 실행한다`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = TestPortfolioRepository(gate)
            val context =
                TestContext(
                    this,
                    MyPageState(
                        portfolioState =
                            MyPagePortfolioState.Uploading(
                                "synthetic.pdf",
                                portfolioId = "accepted",
                            ),
                    ),
                )
            val handler = createHandler(context, repository)

            handler.cancelUpload()
            handler.cancelUpload()
            assertTrue(context.state.value.isSubmitting)
            runCurrent()
            assertEquals(1, repository.statusCalls)
            gate.complete(Unit)
            runCurrent()

            val portfolio = context.state.value.portfolioState
            assertEquals(MyPagePortfolioState.Completed("synthetic.pdf"), portfolio)
            assertEquals(MyPageModalType.UploadAlreadyCompleted, context.state.value.modalType)
            assertFalse(context.state.value.isSubmitting)
            assertEquals(0, repository.deleteCalls)
        }

    @Test
    fun `취소된 이전 파일 읽기 결과는 새 선택을 덮지 않고 임시 파일을 지운다`() =
        runTest {
            val oldResult = CompletableDeferred<Unit>()
            val oldFile = File.createTempFile("synthetic-old", ".pdf")
            val context = TestContext(this, MyPageState())
            val reader =
                object : PortfolioFileReader {
                    override suspend fun clearCache() = Unit

                    override suspend fun read(uri: String): PortfolioFileResult =
                        if (uri == "old") {
                            withContext(NonCancellable) {
                                oldResult.await()
                                PortfolioFileResult.Valid(oldFile, "old.pdf", 1)
                            }
                        } else {
                            PortfolioFileResult.Unreadable("new.pdf")
                        }
                }
            val handler = createHandler(context, TestPortfolioRepository(), reader)
            try {
                handler.selectPortfolioFile("old")
                runCurrent()
                handler.selectPortfolioFile("new")
                runCurrent()
                oldResult.complete(Unit)
                runCurrent()

                val current = context.state.value.portfolioState
                assertEquals(MyPagePortfolioState.Failed("new.pdf"), current)
                assertFalse(oldFile.exists())
            } finally {
                oldFile.delete()
            }
        }

    private fun createHandler(
        context: TestContext,
        repository: TestPortfolioRepository,
        reader: PortfolioFileReader =
            object : PortfolioFileReader {
                override suspend fun clearCache() = Unit

                override suspend fun read(uri: String): PortfolioFileResult = error("사용하지 않음")
            },
    ) = MyPagePortfolioHandler(
        context,
        GetPortfolioOverviewUseCase(repository),
        UploadPortfolioUseCase(repository),
        GetPortfolioStatusUseCase(repository),
        DeletePortfolioUseCase(repository),
        reader,
    )

    private class TestContext(
        private val scope: CoroutineScope,
        initial: MyPageState,
    ) : MviContext<MyPageState, MyPageMutation, MyPageEffect> {
        override val state = MutableStateFlow(initial)
        val effects = mutableListOf<MyPageEffect>()

        override fun dispatch(mutation: MyPageMutation) {
            state.value = MyPageReducer.reduce(state.value, mutation)
        }

        override fun sendEffect(effect: MyPageEffect) {
            effects += effect
        }

        override fun launch(block: suspend CoroutineScope.() -> Unit): Job =
            scope.launch(block = block)
    }

    private class TestPortfolioRepository(
        private val statusGate: CompletableDeferred<Unit>? = null,
    ) : PortfolioRepository {
        var statusCalls = 0
        var deleteCalls = 0

        override suspend fun getPortfolioOverview(): PortfolioOverview =
            PortfolioOverview(null, true, null, true, null)

        override suspend fun getPortfolioStatus(portfolioId: String): PortfolioUploadResult {
            statusCalls += 1
            statusGate?.await()
            return PortfolioUploadResult(portfolioId, PortfolioStatus.READY, null)
        }

        override suspend fun uploadPortfolio(
            file: File,
            fileName: String,
            fileSize: Long?,
            pageCount: Int?,
            contentType: String,
        ): PortfolioUploadResult = error("사용하지 않음")

        override suspend fun deletePortfolio(portfolioId: String): PortfolioDeleteResult {
            deleteCalls += 1
            return PortfolioDeleteResult(portfolioId, "2026-10-01T00:00:00")
        }
    }
}
