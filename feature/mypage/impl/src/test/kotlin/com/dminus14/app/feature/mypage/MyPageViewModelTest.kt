package com.dminus14.app.feature.mypage

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.pdf.PdfInvalidReason
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.PortfolioNotFoundException
import com.dminus14.app.domain.model.AuthSession
import com.dminus14.app.domain.model.InterviewAbandon
import com.dminus14.app.domain.model.InterviewAbandonRequestCause
import com.dminus14.app.domain.model.InterviewMediaFileRef
import com.dminus14.app.domain.model.InterviewMediaManifest
import com.dminus14.app.domain.model.InterviewMediaSegmentType
import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.InterviewReport
import com.dminus14.app.domain.model.InterviewReportList
import com.dminus14.app.domain.model.InterviewReportListItem
import com.dminus14.app.domain.model.InterviewReportStatus
import com.dminus14.app.domain.model.InterviewResumeConfirm
import com.dminus14.app.domain.model.InterviewResumeStatus
import com.dminus14.app.domain.model.InterviewSessionRequest
import com.dminus14.app.domain.model.InterviewSessionResult
import com.dminus14.app.domain.model.InterviewSessionStatus
import com.dminus14.app.domain.model.InterviewUploadNetworkPolicy
import com.dminus14.app.domain.model.InterviewUploadTask
import com.dminus14.app.domain.model.InterviewVideoExpiry
import com.dminus14.app.domain.model.InterviewVideoUploadUrl
import com.dminus14.app.domain.model.JdValidationResult
import com.dminus14.app.domain.model.Portfolio
import com.dminus14.app.domain.model.PortfolioDeleteResult
import com.dminus14.app.domain.model.PortfolioOverview
import com.dminus14.app.domain.model.PortfolioStatus
import com.dminus14.app.domain.model.PortfolioUploadResult
import com.dminus14.app.domain.model.SubmitAnswerResult
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand
import com.dminus14.app.domain.model.UploadInterviewVideoCommand
import com.dminus14.app.domain.model.UserProfile
import com.dminus14.app.domain.model.UserProfileUpdate
import com.dminus14.app.domain.repository.AuthRepository
import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.repository.InterviewRepository
import com.dminus14.app.domain.repository.InterviewWorkController
import com.dminus14.app.domain.repository.PortfolioRepository
import com.dminus14.app.domain.repository.SessionRepository
import com.dminus14.app.domain.repository.UserRepository
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.ClearInterviewLocalDataUseCase
import com.dminus14.app.domain.usecase.DeletePortfolioUseCase
import com.dminus14.app.domain.usecase.GetInterviewReportListUseCase
import com.dminus14.app.domain.usecase.GetPortfolioOverviewUseCase
import com.dminus14.app.domain.usecase.GetPortfolioStatusUseCase
import com.dminus14.app.domain.usecase.LogoutUseCase
import com.dminus14.app.domain.usecase.UploadPortfolioUseCase
import com.dminus14.app.domain.usecase.WithdrawUserUseCase
import com.dminus14.app.feature.mypage.portfolio.PortfolioFileReader
import com.dminus14.app.feature.mypage.portfolio.PortfolioFileResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MyPageViewModelTest {
    @Test
    fun `초기 상태는 로딩되지 않은 빈 화면이다`() {
        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertFalse(state.isInitialLoading)
        assertEquals(null, state.profile)
        assertEquals(MyPagePortfolioState.Empty, state.portfolioState)
        assertTrue(state.reports.isEmpty())
        assertEquals(null, state.modalType)
    }

    @Test
    fun `Load 성공 시 프로필 티켓 소셜 포트폴리오 리포트가 State에 반영된다`() =
        runViewModelTest {
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(samplePortfolio(PortfolioStatus.READY)),
                )
            val viewModel = createViewModel(portfolioRepository = repository)

            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("홍길동", state.profile?.name)
            assertEquals(3, state.remainingTicketCount)
            assertTrue(state.socialAccount?.isKakaoProvider == true)
            assertTrue(state.portfolioState is MyPagePortfolioState.Uploaded)
            assertEquals(1, state.reports.size)
            assertFalse(state.isInitialLoading)
        }

    @Test
    fun `포트폴리오 교체와 삭제 가능 일시는 각각 State에 반영된다`() =
        runViewModelTest {
            val repository =
                FakePortfolioRepository(
                    overview =
                        overviewWith(
                            portfolio = null,
                            nextReplaceAvailableAt = "2026-09-01T00:00:00",
                            nextDeleteAvailableAt = "2026-10-01T00:00:00",
                        ),
                )
            val viewModel = createViewModel(portfolioRepository = repository)

            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            assertEquals(
                "2026-09-01T00:00:00",
                viewModel.state.value.nextPortfolioAvailableAt,
            )
            assertEquals(
                "2026-10-01T00:00:00",
                viewModel.state.value.nextPortfolioDeleteAvailableAt,
            )
        }

    @Test
    fun `리포트 조회만 실패하면 나머지 섹션은 그대로 보여주고 Toast를 한 번 발행한다`() =
        runViewModelTest {
            val failure = PortfolioNotFoundException("PORTFOLIO_NOT_FOUND", "찾을 수 없음")
            val interviewRepository =
                FakeInterviewRepository(reportListResult = Result.failure(failure))
            val viewModel = createViewModel(interviewRepository = interviewRepository)
            val effects = mutableListOf<MyPageEffect>()
            val effectJob = launch { viewModel.effect.collect(effects::add) }

            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            assertEquals(
                "홍길동",
                viewModel.state.value.profile
                    ?.name,
            )
            assertTrue(
                viewModel.state.value.reports
                    .isEmpty(),
            )
            assertEquals(1, effects.filterIsInstance<MyPageEffect.ShowToast>().size)
            effectJob.cancel()
        }

    @Test
    fun `네트워크 오류는 Toast가 아니라 전역 이벤트로 전달된다`() =
        runViewModelTest {
            val failure = NetworkUnavailableException("NETWORK_UNAVAILABLE")
            val userRepository = FakeUserRepository(profileResult = Result.failure(failure))
            val viewModel = createViewModel(userRepository = userRepository)
            val effects = mutableListOf<MyPageEffect>()
            val globalEvents = mutableListOf<GlobalAppEvent>()
            val effectJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.effect.collect(effects::add)
                }
            val globalEventJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    GlobalErrorHandler.events.collect { globalEvents.add(it.event) }
                }

            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            assertTrue(effects.none { it is MyPageEffect.ShowToast })
            assertEquals(GlobalAppEvent.ShowNetworkErrorAndExit, globalEvents.firstOrNull())
            effectJob.cancel()
            globalEventJob.cancel()
        }

    @Test
    fun `진입 시 처리 중인 포트폴리오가 있으면 폴링을 이어서 완료까지 수행한다`() =
        runViewModelTest {
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(samplePortfolio(PortfolioStatus.PROCESSING)),
                    statusResults =
                        mutableListOf(
                            Result.success(statusResult(PortfolioStatus.READY)),
                        ),
                )
            val viewModel = createViewModel(portfolioRepository = repository)

            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            val expected = MyPagePortfolioState.Completed("resume.pdf")
            assertEquals(expected, viewModel.state.value.portfolioState)
        }

    @Test
    fun `진입 시 실패한 포트폴리오가 있으면 실패 카드를 보여준다`() =
        runViewModelTest {
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(samplePortfolio(PortfolioStatus.FAILED_FILE)),
                )
            val viewModel = createViewModel(portfolioRepository = repository)

            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            val portfolioState = viewModel.state.value.portfolioState
            assertTrue(portfolioState is MyPagePortfolioState.Failed)
            val failed = portfolioState as MyPagePortfolioState.Failed
            assertEquals("portfolio-1", failed.portfolioId)
        }

    @Test
    fun `재개 폴링도 2분을 넘기면 실패로 처리한다`() =
        runViewModelTest {
            val processing = Result.success(statusResult(PortfolioStatus.PROCESSING))
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(samplePortfolio(PortfolioStatus.PROCESSING)),
                    defaultStatusResult = processing,
                )
            val viewModel = createViewModel(portfolioRepository = repository)

            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.portfolioState is MyPagePortfolioState.Failed)
            assertEquals(40, repository.statusCallCount)
        }

    @Test
    fun `실패한 포트폴리오를 다시 올리면 기존 실패 레코드를 먼저 삭제한다`() =
        runViewModelTest {
            val readyStatus = statusResult(PortfolioStatus.READY, portfolioId = "new-id")
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(samplePortfolio(PortfolioStatus.FAILED_FILE)),
                    uploadResult = uploadAccepted(),
                    statusResults = mutableListOf(Result.success(readyStatus)),
                )
            val viewModel = createViewModel(portfolioRepository = repository)
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(MyPageIntent.Portfolio.SelectPortfolioFile("content://sample"))
            advanceUntilIdle()

            assertEquals(listOf("portfolio-1"), repository.deletedPortfolioIds)
            assertEquals(1, repository.uploadCallCount)
        }

    @Test
    fun `유효하지 않은 PDF 사유를 실패 상태에 보존한다`() =
        runViewModelTest {
            val reader =
                FakePortfolioFileReader(
                    PortfolioFileResult.Invalid(
                        fileName = "resume.pdf",
                        reason = PdfInvalidReason.PASSWORD_REQUIRED,
                    ),
                )
            val viewModel = createViewModel(portfolioFileReader = reader)

            viewModel.onIntent(MyPageIntent.Portfolio.SelectPortfolioFile("content://sample"))
            advanceUntilIdle()

            assertEquals(
                PdfInvalidReason.PASSWORD_REQUIRED,
                (viewModel.state.value.portfolioState as MyPagePortfolioState.Failed).invalidReason,
            )
        }

    @Test
    fun `실패 레코드 삭제가 이미 없는 대상이면 그대로 업로드를 진행한다`() =
        runViewModelTest {
            val notFound = PortfolioNotFoundException("PORTFOLIO_NOT_FOUND", "없음")
            val readyStatus = statusResult(PortfolioStatus.READY, portfolioId = "new-id")
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(samplePortfolio(PortfolioStatus.FAILED_FILE)),
                    deleteResult = { Result.failure(notFound) },
                    uploadResult = uploadAccepted(),
                    statusResults = mutableListOf(Result.success(readyStatus)),
                )
            val viewModel = createViewModel(portfolioRepository = repository)
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(MyPageIntent.Portfolio.SelectPortfolioFile("content://sample"))
            advanceUntilIdle()

            assertEquals(1, repository.uploadCallCount)
            val expected = MyPagePortfolioState.Completed("resume.pdf")
            assertEquals(expected, viewModel.state.value.portfolioState)
        }

    @Test
    fun `완료 카드에서 닫기를 누르면 바로 지우지 않고 삭제 확인 모달을 띄운다`() =
        runViewModelTest {
            val readyStatus = statusResult(PortfolioStatus.READY, portfolioId = "new-id")
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(portfolio = null),
                    uploadResult = uploadAccepted(),
                    statusResults = mutableListOf(Result.success(readyStatus)),
                )
            val viewModel = createViewModel(portfolioRepository = repository)
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(MyPageIntent.Portfolio.SelectPortfolioFile("content://sample"))
            advanceUntilIdle()
            check(viewModel.state.value.portfolioState is MyPagePortfolioState.Completed)

            viewModel.onIntent(MyPageIntent.Portfolio.ClickPortfolioDelete)

            assertEquals(MyPageModalType.PortfolioDelete, viewModel.state.value.modalType)
            assertTrue(repository.deletedPortfolioIds.none { it == "new-id" })
        }

    @Test
    fun `접수 전 취소는 삭제 API를 호출하지 않는다`() =
        runViewModelTest {
            val uploadDeferred = CompletableDeferred<Result<PortfolioUploadResult>>()
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(portfolio = null),
                    uploadDeferred = uploadDeferred,
                )
            val viewModel = createViewModel(portfolioRepository = repository)
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            // UnconfinedTestDispatcher는 delay() 직전까지 즉시 실행하므로, 폴링이 첫 delay에서
            // 멈춘 이 시점은 접수(202) 이전 전송 중 상태다.
            viewModel.onIntent(MyPageIntent.Portfolio.SelectPortfolioFile("content://sample"))
            val uploading = viewModel.state.value.portfolioState
            assertTrue(uploading is MyPagePortfolioState.Uploading)
            assertNull((uploading as MyPagePortfolioState.Uploading).portfolioId)

            viewModel.onIntent(MyPageIntent.Portfolio.ClickUploadCancel)
            advanceUntilIdle()

            assertTrue(repository.deletedPortfolioIds.isEmpty())
            assertEquals(MyPagePortfolioState.Empty, viewModel.state.value.portfolioState)
        }

    @Test
    fun `접수 후 취소는 상태를 재확인한 뒤 삭제 API를 호출한다`() =
        runViewModelTest {
            val processing = statusResult(PortfolioStatus.PROCESSING, portfolioId = "new-id")
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(portfolio = null),
                    uploadResult = uploadAccepted(),
                    defaultStatusResult = Result.success(processing),
                )
            val viewModel = createViewModel(portfolioRepository = repository)
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()
            // 폴링이 첫 delay에서 멈춘 접수 직후 시점에 취소한다.
            viewModel.onIntent(MyPageIntent.Portfolio.SelectPortfolioFile("content://sample"))

            viewModel.onIntent(MyPageIntent.Portfolio.ClickUploadCancel)
            advanceUntilIdle()

            assertEquals(listOf("new-id"), repository.deletedPortfolioIds)
            assertEquals(MyPagePortfolioState.Empty, viewModel.state.value.portfolioState)
        }

    @Test
    fun `취소 직전 상태가 이미 완료면 삭제하지 않고 완료 안내를 띄운다`() =
        runViewModelTest {
            val ready = statusResult(PortfolioStatus.READY, portfolioId = "new-id")
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(portfolio = null),
                    uploadResult = uploadAccepted(),
                    defaultStatusResult = Result.success(ready),
                )
            val viewModel = createViewModel(portfolioRepository = repository)
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(MyPageIntent.Portfolio.SelectPortfolioFile("content://sample"))

            viewModel.onIntent(MyPageIntent.Portfolio.ClickUploadCancel)
            advanceUntilIdle()

            assertTrue(repository.deletedPortfolioIds.isEmpty())
            assertEquals(MyPageModalType.UploadAlreadyCompleted, viewModel.state.value.modalType)
        }

    @Test
    fun `취소 전 상태 조회가 실패하면 제출 상태를 복구하고 Toast를 발행한다`() =
        runViewModelTest {
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(portfolio = null),
                    uploadResult = uploadAccepted(),
                    defaultStatusResult = Result.failure(IllegalStateException("상태 조회 실패")),
                )
            val viewModel = createViewModel(portfolioRepository = repository)
            val effects = mutableListOf<MyPageEffect>()
            val effectJob = launch { viewModel.effect.collect(effects::add) }
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(MyPageIntent.Portfolio.SelectPortfolioFile("content://sample"))

            viewModel.onIntent(MyPageIntent.Portfolio.ClickUploadCancel)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isSubmitting)
            assertTrue(effects.any { it is MyPageEffect.ShowToast })
            effectJob.cancel()
        }

    @Test
    fun `진행 중 면접이 있으면 삭제 확인 모달 대신 차단 모달을 발행한다`() =
        runViewModelTest {
            val portfolio = samplePortfolio(PortfolioStatus.READY, isInterviewInProgress = true)
            val repository = FakePortfolioRepository(overview = overviewWith(portfolio))
            val viewModel = createViewModel(portfolioRepository = repository)
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(MyPageIntent.Portfolio.ClickPortfolioDelete)

            assertEquals(
                MyPageModalType.PortfolioDeleteUnavailable,
                viewModel.state.value.modalType,
            )
        }

    @Test
    fun `이번 달 교체 기회가 없으면 파일 선택 대신 차단 모달을 발행한다`() =
        runViewModelTest {
            val overview = overviewWith(portfolio = null, isReplaceAvailable = false)
            val repository = FakePortfolioRepository(overview = overview)
            val viewModel = createViewModel(portfolioRepository = repository)
            val effects = mutableListOf<MyPageEffect>()
            val effectJob = launch { viewModel.effect.collect(effects::add) }
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(MyPageIntent.Portfolio.ClickPortfolioReupload)

            assertEquals(
                MyPageModalType.PortfolioReuploadUnavailable,
                viewModel.state.value.modalType,
            )
            assertTrue(effects.none { it is MyPageEffect.PortfolioSelectionRequested })
            effectJob.cancel()
        }

    @Test
    fun `최초 업로드는 정책 고지 모달 없이 파일 선택을 요청한다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(MyPageIntent.Portfolio.ClickUpload)

            assertEquals(MyPageEffect.PortfolioSelectionRequested, effect.await())
            assertEquals(null, viewModel.state.value.modalType)
        }

    @Test
    fun `로그아웃은 서버 폐기가 실패해도 완료 효과를 발행하고 로컬 세션을 지운다`() =
        runViewModelTest {
            val sessionRepository = FakeSessionRepository()
            val serverFailure = Result.failure<Unit>(IllegalStateException("서버 실패"))
            val authRepository = FakeAuthRepository(logoutResult = serverFailure)
            val viewModel =
                createViewModel(
                    authRepository = authRepository,
                    sessionRepository = sessionRepository,
                )
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(MyPageIntent.Account.ClickLogout)
            viewModel.onIntent(MyPageIntent.ConfirmModal)

            assertEquals(MyPageEffect.LogoutCompleted, effect.await())
            assertEquals(1, sessionRepository.clearCallCount)
        }

    @Test
    fun `삭제가 실패해도 모달이 닫히고 Toast가 발행된다`() =
        runViewModelTest {
            val deleteFailure =
                Result.failure<PortfolioDeleteResult>(
                    IllegalStateException("삭제 실패"),
                )
            val repository =
                FakePortfolioRepository(
                    overview = overviewWith(samplePortfolio(PortfolioStatus.READY)),
                    deleteResult = { deleteFailure },
                )
            val viewModel = createViewModel(portfolioRepository = repository)
            val effects = mutableListOf<MyPageEffect>()
            val effectJob = launch { viewModel.effect.collect(effects::add) }
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(MyPageIntent.Portfolio.ClickPortfolioDelete)
            viewModel.onIntent(MyPageIntent.ConfirmModal)
            advanceUntilIdle()

            assertEquals(null, viewModel.state.value.modalType)
            assertTrue(effects.any { it is MyPageEffect.ShowToast })
            effectJob.cancel()
        }

    @Test
    fun `탈퇴가 실패해도 모달이 닫히고 계정은 그대로 남는다`() =
        runViewModelTest {
            val withdrawFailure = Result.failure<Unit>(IllegalStateException("탈퇴 실패"))
            val userRepository = FakeUserRepository(withdrawResult = withdrawFailure)
            val viewModel = createViewModel(userRepository = userRepository)
            val effects = mutableListOf<MyPageEffect>()
            val effectJob = launch { viewModel.effect.collect(effects::add) }

            viewModel.onIntent(MyPageIntent.Account.ClickWithdrawal)
            viewModel.onIntent(MyPageIntent.ConfirmModal)
            viewModel.onIntent(MyPageIntent.ConfirmModal)
            advanceUntilIdle()

            assertEquals(null, viewModel.state.value.modalType)
            assertTrue(effects.none { it is MyPageEffect.WithdrawalCompleted })
            assertTrue(effects.any { it is MyPageEffect.ShowToast })
            effectJob.cancel()
        }

    @Test
    fun `탈퇴는 주의사항 모달만으로는 실행되지 않고 최종 확인에서만 실행된다`() =
        runViewModelTest {
            val userRepository = FakeUserRepository()
            val viewModel = createViewModel(userRepository = userRepository)

            viewModel.onIntent(MyPageIntent.Account.ClickWithdrawal)
            assertEquals(MyPageModalType.WithdrawalNotice, viewModel.state.value.modalType)

            viewModel.onIntent(MyPageIntent.ConfirmModal)
            assertEquals(MyPageModalType.WithdrawalConfirm, viewModel.state.value.modalType)
            assertFalse(userRepository.withdrawCalled)

            viewModel.onIntent(MyPageIntent.ConfirmModal)
            advanceUntilIdle()

            assertTrue(userRepository.withdrawCalled)
        }

    @Test
    fun `탈퇴 최종 확인을 연속으로 눌러도 탈퇴 요청은 한 번만 실행된다`() =
        runViewModelTest {
            val withdrawGate = CompletableDeferred<Unit>()
            val userRepository = FakeUserRepository(withdrawGate = withdrawGate)
            val viewModel = createViewModel(userRepository = userRepository)
            viewModel.onIntent(MyPageIntent.Account.ClickWithdrawal)
            viewModel.onIntent(MyPageIntent.ConfirmModal)

            viewModel.onIntent(MyPageIntent.ConfirmModal)
            viewModel.onIntent(MyPageIntent.ConfirmModal)

            assertEquals(1, userRepository.withdrawCallCount)
            withdrawGate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `진행 중 면접이 있으면 탈퇴 주의사항 모달 대신 차단 모달을 발행한다`() =
        runViewModelTest {
            val portfolio = samplePortfolio(PortfolioStatus.READY, isInterviewInProgress = true)
            val repository = FakePortfolioRepository(overview = overviewWith(portfolio))
            val viewModel = createViewModel(portfolioRepository = repository)
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(MyPageIntent.Account.ClickWithdrawal)

            assertEquals(MyPageModalType.WithdrawalBlocked, viewModel.state.value.modalType)
        }

    @Test
    fun `티켓 수가 없으면 안내 말풍선을 표시하지 않는다`() =
        runViewModelTest {
            val profile = sampleUserProfile.copy(remainingTicketCount = null)
            val userRepository = FakeUserRepository(profileResult = Result.success(profile))
            val viewModel = createViewModel(userRepository = userRepository)
            viewModel.onIntent(MyPageIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(MyPageIntent.Account.ClickTicketInfo)

            assertFalse(viewModel.state.value.isTicketInfoTooltipVisible)
        }

    // ---- 테스트 유틸 ----

    private fun runViewModelTest(block: suspend TestScope.() -> Unit) =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                block()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Suppress("LongParameterList")
    private fun createViewModel(
        userRepository: UserRepository = FakeUserRepository(),
        portfolioRepository: PortfolioRepository = FakePortfolioRepository(),
        interviewRepository: InterviewRepository = FakeInterviewRepository(),
        authRepository: AuthRepository = FakeAuthRepository(),
        sessionRepository: SessionRepository = FakeSessionRepository(),
        portfolioFileReader: PortfolioFileReader = FakePortfolioFileReader(),
    ): MyPageViewModel {
        val clearInterviewLocalData =
            ClearInterviewLocalDataUseCase(
                NoOpInterviewLocalRepository(),
                NoOpInterviewWorkController(),
            )
        return MyPageViewModel(
            checkUserProfileUseCase = CheckUserProfileUseCase(userRepository),
            getPortfolioOverviewUseCase = GetPortfolioOverviewUseCase(portfolioRepository),
            uploadPortfolioUseCase = UploadPortfolioUseCase(portfolioRepository),
            getPortfolioStatusUseCase = GetPortfolioStatusUseCase(portfolioRepository),
            deletePortfolioUseCase = DeletePortfolioUseCase(portfolioRepository),
            getInterviewReportListUseCase = GetInterviewReportListUseCase(interviewRepository),
            logoutUseCase =
                LogoutUseCase(
                    authRepository,
                    sessionRepository,
                    clearInterviewLocalData,
                ),
            withdrawUserUseCase =
                WithdrawUserUseCase(userRepository, sessionRepository, clearInterviewLocalData),
            portfolioFileReader = portfolioFileReader,
        )
    }

    private class NoOpInterviewLocalRepository : InterviewLocalRepository {
        override suspend fun getProgress(): InterviewProgress? = null

        override suspend fun saveProgress(progress: InterviewProgress) = Unit

        override suspend fun updateProgress(
            transform: (InterviewProgress) -> InterviewProgress,
        ): InterviewProgress? = null

        override suspend fun clearProgress() = Unit

        override suspend fun getManifest(sessionId: Long): InterviewMediaManifest? = null

        override suspend fun getUploadManifest(uploadTaskId: String): InterviewMediaManifest? = null

        override suspend fun saveManifest(manifest: InterviewMediaManifest) = Unit

        override suspend fun createMediaFile(
            sessionId: Long,
            type: InterviewMediaSegmentType,
            extension: String,
        ): InterviewMediaFileRef = error("사용하지 않음")

        override suspend fun createUploadMediaFile(
            uploadTaskId: String,
            extension: String,
        ): InterviewMediaFileRef = error("사용하지 않음")

        override suspend fun deleteMediaFile(ref: InterviewMediaFileRef) = Unit

        override suspend fun handoffUploadTask(task: InterviewUploadTask) = Unit

        override suspend fun getUploadTask(uploadTaskId: String): InterviewUploadTask? = null

        override suspend fun saveUploadTask(task: InterviewUploadTask) = Unit

        override suspend fun getUploadTasks(): List<InterviewUploadTask> = emptyList()

        override suspend fun deleteUploadTask(uploadTaskId: String) = Unit

        override suspend fun deleteSession(sessionId: Long) = Unit

        override suspend fun clearAll() = Unit

        override suspend fun isCleanupPending(): Boolean = false

        override suspend fun setCleanupPending(isPending: Boolean) = Unit
    }

    private class NoOpInterviewWorkController : InterviewWorkController {
        override suspend fun enqueueUpload(
            uploadTaskId: String,
            networkPolicy: InterviewUploadNetworkPolicy,
        ) = Unit

        override suspend fun enqueueRetentionCleanup(deadlineEpochMillis: Long) = Unit

        override suspend fun isUploadRunningOrPending(uploadTaskId: String): Boolean = false

        override suspend fun cancelUpload(uploadTaskId: String) = Unit

        override suspend fun cancelAll() = Unit
    }

    private fun uploadAccepted(portfolioId: String = "new-id"): Result<PortfolioUploadResult> =
        Result.success(PortfolioUploadResult(portfolioId, PortfolioStatus.PROCESSING, null))

    private fun overviewWith(
        portfolio: Portfolio?,
        isReplaceAvailable: Boolean = true,
        isDeleteAvailable: Boolean = true,
        nextReplaceAvailableAt: String? = null,
        nextDeleteAvailableAt: String? = null,
    ) = PortfolioOverview(
        portfolio = portfolio,
        isReplaceAvailable = isReplaceAvailable,
        nextReplaceAvailableAt = nextReplaceAvailableAt,
        isDeleteAvailable = isDeleteAvailable,
        nextDeleteAvailableAt = nextDeleteAvailableAt,
    )

    private fun samplePortfolio(
        status: PortfolioStatus,
        portfolioId: String = "portfolio-1",
        isInterviewInProgress: Boolean = false,
    ) = Portfolio(
        portfolioId = portfolioId,
        fileName = "resume.pdf",
        fileSize = 1_024L,
        pageCount = 3,
        status = status,
        uploadedAt = "2026-08-04T00:00:00",
        isInterviewInProgress = isInterviewInProgress,
    )

    private fun statusResult(
        status: PortfolioStatus,
        portfolioId: String = "portfolio-1",
    ) = PortfolioUploadResult(portfolioId = portfolioId, status = status, message = null)

    private companion object {
        val sampleUserProfile =
            UserProfile(
                name = "홍길동",
                email = "sample@kakao.com",
                provider = "KAKAO",
                jobRole = "ANDROID",
                jobRoleLabel = "Android",
                careerYears = 3,
                remainingTicketCount = 3,
            )

        val sampleReportItem =
            InterviewReportListItem(
                sessionId = 1L,
                jobType = "ANDROID",
                jobTypeLabel = "Android",
                careerYears = 3,
                interviewedAt = "2026-08-04T00:00:00",
                portfolioFileName = "resume.pdf",
                portfolioDeleted = false,
                jdUrl = "https://careers.example.com/jobs/1",
                reportStatus = InterviewReportStatus.READY,
                feedbackAvailable = true,
            )

        val sampleReportList = InterviewReportList(reports = listOf(sampleReportItem))
    }

    private class FakeUserRepository(
        private val profileResult: Result<UserProfile> = Result.success(sampleUserProfile),
        private val withdrawResult: Result<Unit> = Result.success(Unit),
        private val withdrawGate: CompletableDeferred<Unit>? = null,
    ) : UserRepository {
        var withdrawCalled = false
            private set
        var withdrawCallCount = 0
            private set

        override suspend fun getUserProfile(): UserProfile = profileResult.getOrThrow()

        override suspend fun updateUserProfile(update: UserProfileUpdate) = Unit

        override suspend fun withdraw() {
            withdrawCalled = true
            withdrawCallCount += 1
            withdrawGate?.await()
            withdrawResult.getOrThrow()
        }

        override suspend fun getJobList() = error("사용하지 않음")
    }

    private class FakeSessionRepository(
        private val clearResult: Result<Unit> = Result.success(Unit),
    ) : SessionRepository {
        var clearCallCount = 0
            private set

        override suspend fun getAuthSession(): AuthSession? = null

        override suspend fun refreshToken(refreshToken: String): AuthSession = error("사용하지 않음")

        override suspend fun saveAuthSession(
            accessToken: String,
            refreshToken: String,
        ): AuthSession = error("사용하지 않음")

        override suspend fun clearAuthSession() {
            clearCallCount += 1
            clearResult.getOrThrow()
        }
    }

    private class FakeAuthRepository(
        private val logoutResult: Result<Unit> = Result.success(Unit),
    ) : AuthRepository {
        override suspend fun loginWithKakao(credential: String): AuthSession = error("사용하지 않음")

        override suspend fun logout() {
            logoutResult.getOrThrow()
        }
    }

    private class FakePortfolioRepository(
        private val overview: PortfolioOverview = PortfolioOverview(null, true, null, true, null),
        private val uploadResult: Result<PortfolioUploadResult> = uploadAcceptedDefault(),
        private val uploadDeferred: CompletableDeferred<Result<PortfolioUploadResult>>? = null,
        private val statusResults: MutableList<Result<PortfolioUploadResult>> = mutableListOf(),
        private val defaultStatusResult: Result<PortfolioUploadResult> = statusDefault(),
        private val deleteResult: (String) -> Result<PortfolioDeleteResult> = ::deleteDefault,
    ) : PortfolioRepository {
        val deletedPortfolioIds = mutableListOf<String>()
        var uploadCallCount = 0
            private set
        var statusCallCount = 0
            private set

        override suspend fun getPortfolioOverview(): PortfolioOverview = overview

        override suspend fun uploadPortfolio(
            file: File,
            fileName: String,
            fileSize: Long?,
            pageCount: Int?,
            contentType: String,
        ): PortfolioUploadResult {
            uploadCallCount += 1
            val result = uploadDeferred?.await() ?: uploadResult
            return result.getOrThrow()
        }

        override suspend fun getPortfolioStatus(portfolioId: String): PortfolioUploadResult {
            val result = statusResults.getOrElse(statusCallCount) { defaultStatusResult }
            statusCallCount += 1
            return result.getOrThrow()
        }

        override suspend fun deletePortfolio(portfolioId: String): PortfolioDeleteResult {
            deletedPortfolioIds += portfolioId
            return deleteResult(portfolioId).getOrThrow()
        }

        private companion object {
            fun uploadAcceptedDefault() =
                Result.success(PortfolioUploadResult("new-id", PortfolioStatus.PROCESSING, null))

            fun statusDefault() =
                Result.success(
                    PortfolioUploadResult("portfolio-1", PortfolioStatus.PROCESSING, null),
                )

            fun deleteDefault(id: String) =
                Result.success(PortfolioDeleteResult(id, "2026-08-04T00:00:00"))
        }
    }

    private class FakeInterviewRepository(
        private val reportListResult: Result<InterviewReportList> =
            Result.success(
                sampleReportList,
            ),
    ) : InterviewRepository {
        override suspend fun validateJdUrl(jdUrl: String): JdValidationResult = error("사용하지 않음")

        override suspend fun createInterviewSession(
            request: InterviewSessionRequest,
        ): InterviewSessionResult = error("사용하지 않음")

        override suspend fun getInterviewSession(sessionId: Long): InterviewSessionStatus =
            error("사용하지 않음")

        override suspend fun getInterviewSessionStatus(sessionId: Long): InterviewSessionStatus =
            error("사용하지 않음")

        override suspend fun getReportList(): InterviewReportList = reportListResult.getOrThrow()

        override suspend fun submitAnswer(
            command: SubmitInterviewAnswerCommand,
        ): SubmitAnswerResult = error("사용하지 않음")

        override fun getAudioStreamUrl(
            sessionId: Long,
            questionId: Long,
        ): String = error("사용하지 않음")

        override suspend fun getResume(sessionId: Long): InterviewResumeStatus = error("사용하지 않음")

        override suspend fun confirmResume(sessionId: Long): InterviewResumeConfirm =
            error("사용하지 않음")

        override suspend fun abandon(
            sessionId: Long,
            cause: InterviewAbandonRequestCause,
        ): InterviewAbandon = error("사용하지 않음")

        override suspend fun uploadVideo(command: UploadInterviewVideoCommand) = error("사용하지 않음")

        override suspend fun getReport(sessionId: Long): InterviewReport = error("사용하지 않음")

        override suspend fun issueUploadUrl(sessionId: Long): InterviewVideoUploadUrl =
            error("사용하지 않음")

        override suspend fun completeUpload(
            sessionId: Long,
            wrapUpStartSec: Float?,
            wrapUpEndSec: Float?,
        ) = error("사용하지 않음")

        override suspend fun getExpiry(sessionId: Long): InterviewVideoExpiry = error("사용하지 않음")
    }

    private class FakePortfolioFileReader(
        private val result: PortfolioFileResult =
            PortfolioFileResult.Valid(
                File("resume.pdf"),
                "resume.pdf",
                pageCount = 3,
            ),
    ) : PortfolioFileReader {
        override suspend fun read(uri: String): PortfolioFileResult = result

        override suspend fun clearCache() = Unit
    }
}
