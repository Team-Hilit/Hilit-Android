package com.dminus14.app.feature.home

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.domain.exception.InterviewSessionAlreadyEndedException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.exception.UserNotFoundException
import com.dminus14.app.domain.model.InterviewAbandon
import com.dminus14.app.domain.model.InterviewAbandonCause
import com.dminus14.app.domain.model.InterviewAbandonRequestCause
import com.dminus14.app.domain.model.InterviewProgress
import com.dminus14.app.domain.model.InterviewReport
import com.dminus14.app.domain.model.InterviewReportList
import com.dminus14.app.domain.model.InterviewReportListItem
import com.dminus14.app.domain.model.InterviewReportStatus
import com.dminus14.app.domain.model.InterviewResumeConfirm
import com.dminus14.app.domain.model.InterviewResumeState
import com.dminus14.app.domain.model.InterviewResumeStatus
import com.dminus14.app.domain.model.InterviewSessionRequest
import com.dminus14.app.domain.model.InterviewSessionResult
import com.dminus14.app.domain.model.InterviewSessionStatus
import com.dminus14.app.domain.model.InterviewTerminalStatus
import com.dminus14.app.domain.model.InterviewTicketOutcome
import com.dminus14.app.domain.model.InterviewVideoExpiry
import com.dminus14.app.domain.model.InterviewVideoUploadUrl
import com.dminus14.app.domain.model.JdValidationResult
import com.dminus14.app.domain.model.SubmitAnswerResult
import com.dminus14.app.domain.model.SubmitInterviewAnswerCommand
import com.dminus14.app.domain.model.UploadInterviewVideoCommand
import com.dminus14.app.domain.model.UserProfile
import com.dminus14.app.domain.model.UserProfileUpdate
import com.dminus14.app.domain.repository.InterviewLocalRepository
import com.dminus14.app.domain.repository.InterviewRepository
import com.dminus14.app.domain.repository.UserRepository
import com.dminus14.app.domain.time.InterviewClock
import com.dminus14.app.domain.time.InterviewTimeCalculator
import com.dminus14.app.domain.usecase.AbandonInterviewUseCase
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.GetInterviewElapsedTimeUseCase
import com.dminus14.app.domain.usecase.GetInterviewProgressUseCase
import com.dminus14.app.domain.usecase.GetInterviewReportListUseCase
import com.dminus14.app.domain.usecase.GetInterviewResumeUseCase
import com.dminus14.app.domain.usecase.RetainInterviewSessionForCleanupUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("detekt:LargeClass") // MVI 케이스가 많아 임계를 살짝 넘는다.
class HomeViewModelTest {
    // ---- Load 성공 경로 ----

    @Test
    fun `Load 시 프로필 조회가 끝나기 전까지 isLoading이 true로 유지된다`() =
        runViewModelTest {
            val profileGate = CompletableDeferred<Unit>()
            val viewModel =
                createViewModel(
                    userRepository = FakeUserRepository(profileGate = profileGate),
                )

            viewModel.onIntent(HomeIntent.Load)

            assertTrue(viewModel.state.value.isLoading)
            profileGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isLoading)
        }

    @Test
    fun `Load 성공 시 프로필과 리포트가 반영되고 첫 리포트만 기본 확장된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            reportListResult =
                                Result.success(
                                    InterviewReportList(
                                        reports =
                                            listOf(reportItem(1L), reportItem(2L), reportItem(3L)),
                                    ),
                                ),
                        ),
                )

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("홍길동", state.userName)
            assertEquals(3, state.remainingTicketCount)
            assertEquals(3, state.reports.size)
            assertEquals(setOf("1"), state.expandedReportIds)
            assertFalse(state.isLoading)
        }

    @Test
    fun `리포트가 빈 목록이면 reports와 expandedReportIds가 모두 비어 있다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            reportListResult =
                                Result.success(InterviewReportList(reports = emptyList())),
                        ),
                )

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.reports.isEmpty())
            assertTrue(state.expandedReportIds.isEmpty())
            assertFalse(state.isLoading)
        }

    @Test
    fun `리포트 상세를 봤다가 홈으로 돌아와 Load가 재발행돼도 사용자가 펼친 카드 상태가 유지된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            reportListResult =
                                Result.success(
                                    InterviewReportList(
                                        reports =
                                            listOf(reportItem(1L), reportItem(2L), reportItem(3L)),
                                    ),
                                ),
                        ),
                )

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ClickReportExpand("2"))
            assertEquals(setOf("1", "2"), viewModel.state.value.expandedReportIds)

            // 리포트 상세로 이동했다가 뒤로가기로 홈에 재진입하면 HomeScreen의
            // LaunchedEffect(Unit)이 다시 실행돼 Load가 한 번 더 발행된다.
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            assertEquals(setOf("1", "2"), viewModel.state.value.expandedReportIds)
        }

    @Test
    fun `재조회 사이 삭제된 리포트 id는 펼침 상태에서도 제거된다`() =
        runViewModelTest {
            val firstReports =
                Result.success(
                    InterviewReportList(reports = listOf(reportItem(1L), reportItem(2L))),
                )
            val fakeRepository = FakeInterviewRepository(reportListResult = firstReports)
            val viewModel = createViewModel(interviewRepository = fakeRepository)

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ClickReportExpand("2"))
            assertEquals(setOf("1", "2"), viewModel.state.value.expandedReportIds)

            fakeRepository.reportListResult =
                Result.success(InterviewReportList(reports = listOf(reportItem(1L))))
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            assertEquals(setOf("1"), viewModel.state.value.expandedReportIds)
        }

    @Test
    fun `리포트 조회가 실패해도 빈 목록으로 처리되고 Effect는 발행되지 않는다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            reportListResult = Result.failure(IllegalStateException("리포트 실패")),
                        ),
                )
            val effects = mutableListOf<HomeEffect>()
            val effectJob = launch { viewModel.effect.collect(effects::add) }

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("홍길동", state.userName)
            assertTrue(state.reports.isEmpty())
            assertFalse(state.isLoading)
            assertTrue(effects.isEmpty())
            effectJob.cancel()
        }

    // ---- Load 프로필 라우팅 실패 ----

    @Test
    fun `프로필 이름이 null이면 UserNameNotRegistered Effect를 발행하고 리포트를 조회하지 않는다`() =
        runViewModelTest {
            val interviewRepo = FakeInterviewRepository()
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult = Result.success(sampleUserProfile.copy(name = null)),
                        ),
                    interviewRepository = interviewRepo,
                )
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            assertEquals(HomeEffect.UserNameNotRegistered, effect.await())
            assertFalse(viewModel.state.value.isLoading)
            assertEquals(0, interviewRepo.getReportListCallCount)
        }

    @Test
    fun `프로필 이름이 공백 문자열이면 UserNameNotRegistered Effect를 발행한다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult = Result.success(sampleUserProfile.copy(name = "   ")),
                        ),
                )
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            assertEquals(HomeEffect.UserNameNotRegistered, effect.await())
        }

    @Test
    fun `프로필 실패가 UserNotFoundException이면 UserNotFound Effect를 발행하고 리포트를 조회하지 않는다`() =
        runViewModelTest {
            val interviewRepo = FakeInterviewRepository()
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult =
                                Result.failure(UserNotFoundException(errCode = "USER_NOT_FOUND")),
                        ),
                    interviewRepository = interviewRepo,
                )
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            assertEquals(HomeEffect.UserNotFound, effect.await())
            assertFalse(viewModel.state.value.isLoading)
            assertEquals(0, interviewRepo.getReportListCallCount)
        }

    // ---- Load 공통 에러 (handleBootstrapFailure) ----

    @Test
    fun `프로필 실패가 NetworkUnavailableException이면 ShowNetworkErrorAndExit이 전역 이벤트로 발행된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult =
                                Result.failure(
                                    NetworkUnavailableException(errCode = "NETWORK_UNAVAILABLE"),
                                ),
                        ),
                )
            val globalEvents = mutableListOf<GlobalAppEvent>()
            val globalJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    GlobalErrorHandler.events.collect { globalEvents.add(it.event) }
                }

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            assertEquals(GlobalAppEvent.ShowNetworkErrorAndExit, globalEvents.firstOrNull())
            assertFalse(viewModel.state.value.isLoading)
            globalJob.cancel()
        }

    @Test
    fun `프로필 실패가 ServerException이면 ShowServerErrorAndExit이 전역 이벤트로 발행된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult = Result.failure(ServerException(errCode = "SERVER")),
                        ),
                )
            val globalEvents = mutableListOf<GlobalAppEvent>()
            val globalJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    GlobalErrorHandler.events.collect { globalEvents.add(it.event) }
                }

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            assertEquals(GlobalAppEvent.ShowServerErrorAndExit, globalEvents.firstOrNull())
            globalJob.cancel()
        }

    @Test
    fun `프로필 실패가 기타 예외이면 ShowUnknownError가 전역 이벤트로 발행된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult = Result.failure(IllegalStateException("unknown")),
                        ),
                )
            val globalEvents = mutableListOf<GlobalAppEvent>()
            val globalJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    GlobalErrorHandler.events.collect { globalEvents.add(it.event) }
                }

            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            assertEquals(GlobalAppEvent.ShowUnknownError, globalEvents.firstOrNull())
            globalJob.cancel()
        }

    // ---- ClickMyPage ----

    @Test
    fun `ClickMyPage 인텐트는 GoToMyPageRequested Effect를 발행한다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(HomeIntent.ClickMyPage)

            assertEquals(HomeEffect.GoToMyPageRequested, effect.await())
        }

    // ---- ClickReportExpand ----

    @Test
    fun `접혀 있는 리포트 id를 클릭하면 expandedReportIds에 추가된다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            viewModel.onIntent(HomeIntent.ClickReportExpand("A"))

            assertEquals(setOf("A"), viewModel.state.value.expandedReportIds)
        }

    @Test
    fun `이미 확장된 리포트 id를 다시 클릭하면 제거된다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            viewModel.onIntent(HomeIntent.ClickReportExpand("A"))

            viewModel.onIntent(HomeIntent.ClickReportExpand("A"))

            assertTrue(
                viewModel.state.value.expandedReportIds
                    .isEmpty(),
            )
        }

    @Test
    fun `서로 다른 리포트 id를 연속 클릭하면 모두 확장 상태로 누적된다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            viewModel.onIntent(HomeIntent.ClickReportExpand("A"))
            viewModel.onIntent(HomeIntent.ClickReportExpand("B"))

            assertEquals(setOf("A", "B"), viewModel.state.value.expandedReportIds)
        }

    // ---- ClickReportOpen ----

    @Test
    fun `ClickReportOpen 인텐트는 해당 reportId로 GoToReportRequested Effect를 발행한다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(HomeIntent.ClickReportOpen("42"))

            assertEquals(HomeEffect.GoToReportRequested("42"), effect.await())
        }

    @Test
    fun `ClickReportOpen을 서로 다른 id로 연속 호출하면 순서대로 Effect가 발행된다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            val effects = mutableListOf<HomeEffect>()
            val job = launch { viewModel.effect.collect(effects::add) }

            viewModel.onIntent(HomeIntent.ClickReportOpen("1"))
            viewModel.onIntent(HomeIntent.ClickReportOpen("2"))
            advanceUntilIdle()

            assertEquals(
                listOf(
                    HomeEffect.GoToReportRequested("1"),
                    HomeEffect.GoToReportRequested("2"),
                ),
                effects,
            )
            job.cancel()
        }

    @Test
    fun `ClickReportOpen은 state를 바꾸지 않는다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            val before = viewModel.state.value
            val effectJob = launch { viewModel.effect.collect { /* drain */ } }

            viewModel.onIntent(HomeIntent.ClickReportOpen("1"))
            advanceUntilIdle()

            assertEquals(before, viewModel.state.value)
            effectJob.cancel()
        }

    // ---- ClickSessionStart ----

    @Test
    fun `잔여 이용권이 있으면 ClickSessionStart는 GoToOnboardingInterviewRequested Effect를 발행한다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(HomeIntent.ClickSessionStart)

            assertEquals(HomeEffect.GoToOnboardingInterviewRequested, effect.await())
            assertNull(viewModel.state.value.sessionStartOverlay)
        }

    @Test
    fun `잔여 이용권이 0이면 ClickSessionStart는 NoTickets 오버레이만 세팅한다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult =
                                Result.success(
                                    sampleUserProfile.copy(remainingTicketCount = 0),
                                ),
                        ),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ClickSessionStart)

            assertTrue(
                viewModel.state.value.sessionStartOverlay is HomeSessionStartOverlayState.NoTickets,
            )
        }

    @Test
    fun `잔여 이용권이 null이면 ClickSessionStart는 NoTickets 오버레이를 세팅한다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult =
                                Result.success(
                                    sampleUserProfile.copy(remainingTicketCount = null),
                                ),
                        ),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ClickSessionStart)

            assertTrue(
                viewModel.state.value.sessionStartOverlay is HomeSessionStartOverlayState.NoTickets,
            )
        }

    // ---- ClickSessionOverlayDismiss ----

    @Test
    fun `ClickSessionOverlayDismiss는 오버레이를 닫고 ReportSheetResetRequested Effect를 발행한다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult =
                                Result.success(
                                    sampleUserProfile.copy(remainingTicketCount = 0),
                                ),
                        ),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ClickSessionStart)
            check(
                viewModel.state.value.sessionStartOverlay is HomeSessionStartOverlayState.NoTickets,
            )
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(HomeIntent.ClickSessionOverlayDismiss)

            assertNull(viewModel.state.value.sessionStartOverlay)
            assertEquals(HomeEffect.ReportSheetResetRequested, effect.await())
        }

    // ---- ReportSheetCollapsed - 활성 세션 없음 ----

    @Test
    fun `활성 세션이 없고 잔여 이용권이 있으면 ReportSheetCollapsed는 Start 오버레이를 세팅한다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            val overlay = viewModel.state.value.sessionStartOverlay
            assertTrue(overlay is HomeSessionStartOverlayState.Start)
            assertEquals(
                3,
                (overlay as HomeSessionStartOverlayState.Start).remainingTicketCount,
            )
        }

    @Test
    fun `활성 세션이 없고 잔여 이용권이 0이면 ReportSheetCollapsed는 NoTickets 오버레이를 세팅한다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult =
                                Result.success(
                                    sampleUserProfile.copy(remainingTicketCount = 0),
                                ),
                        ),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            assertTrue(
                viewModel.state.value.sessionStartOverlay is HomeSessionStartOverlayState.NoTickets,
            )
        }

    @Test
    fun `활성 세션이 없고 잔여 이용권이 null이면 ReportSheetCollapsed는 NoTickets 오버레이를 세팅한다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult =
                                Result.success(
                                    sampleUserProfile.copy(remainingTicketCount = null),
                                ),
                        ),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            assertTrue(
                viewModel.state.value.sessionStartOverlay is HomeSessionStartOverlayState.NoTickets,
            )
        }

    // ---- ReportSheetCollapsed - 로컬 progress 있음 (checkInterviewSession) ----

    @Test
    fun `로컬 progress RESUMABLE이면 ReportSheetCollapsed는 InProgress 오버레이를 세팅한다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult =
                        Result.success(resumeStatus(InterviewResumeState.Resumable)),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 777L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            val overlay = viewModel.state.value.sessionStartOverlay
            assertTrue(overlay is HomeSessionStartOverlayState.InProgress)
            assertEquals("홍길동", (overlay as HomeSessionStartOverlayState.InProgress).userName)
            // sampleProgress는 타이머 필드가 전부 null이라 elapsed=0, remaining=12분 -> 규칙상 4개.
            assertEquals(4, overlay.remainingQuestionCount)
            assertEquals(listOf(777L), interviewRepo.resumeSessionIds)
        }

    @Test
    fun `로컬 progress ENDED·이용권 있으면 ReportSheetCollapsed는 Start 오버레이를 세팅한다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult =
                        Result.success(resumeStatus(InterviewResumeState.Ended)),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 42L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            val overlay = viewModel.state.value.sessionStartOverlay
            assertTrue(overlay is HomeSessionStartOverlayState.Start)
            assertEquals(
                3,
                (overlay as HomeSessionStartOverlayState.Start).remainingTicketCount,
            )
            assertEquals(listOf(42L), interviewRepo.resumeSessionIds)
        }

    @Test
    fun `로컬 progress ENDED·이용권 0이면 ReportSheetCollapsed는 NoTickets 오버레이를 세팅한다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult =
                        Result.success(resumeStatus(InterviewResumeState.Ended)),
                )
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult =
                                Result.success(
                                    sampleUserProfile.copy(remainingTicketCount = 0),
                                ),
                        ),
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 42L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            assertTrue(
                viewModel.state.value.sessionStartOverlay is HomeSessionStartOverlayState.NoTickets,
            )
            assertEquals(listOf(42L), interviewRepo.resumeSessionIds)
        }

    @Test
    fun `로컬 progress resumeState 미정의면 ReportSheetCollapsed는 오버레이를 띄우지 않는다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult =
                        Result.success(
                            resumeStatus(InterviewResumeState.Unknown("UNKNOWN_RAW")),
                        ),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 42L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            assertNull(viewModel.state.value.sessionStartOverlay)
            assertEquals(listOf(42L), interviewRepo.resumeSessionIds)
        }

    @Test
    fun `로컬 progress resume 조회 실패면 ReportSheetCollapsed는 네트워크 오류 이벤트를 발행한다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult =
                        Result.failure(NetworkUnavailableException(errCode = "NETWORK")),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 99L),
                )
            val globalEvents = mutableListOf<GlobalAppEvent>()
            val eventJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    GlobalErrorHandler.events.collect { globalEvents.add(it.event) }
                }
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            assertEquals(GlobalAppEvent.ShowNetworkErrorAndExit, globalEvents.firstOrNull())
            assertEquals(listOf(99L), interviewRepo.resumeSessionIds)
            eventJob.cancel()
        }

    // ---- 진행 중 세션의 응답별 상태와 오류 ----

    @Test
    fun `세션 확인 시 RESUMABLE이면 InProgress 오버레이가 세팅된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult =
                        Result.success(resumeStatus(InterviewResumeState.Resumable)),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 777L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            val overlay = viewModel.state.value.sessionStartOverlay
            assertTrue(overlay is HomeSessionStartOverlayState.InProgress)
            assertEquals("홍길동", (overlay as HomeSessionStartOverlayState.InProgress).userName)
            assertEquals(listOf(777L), interviewRepo.resumeSessionIds)
        }

    @Test
    fun `세션 확인 시 ENDED에 잔여 이용권이 있으면 Start 오버레이가 세팅된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult =
                        Result.success(resumeStatus(InterviewResumeState.Ended)),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 42L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            val overlay = viewModel.state.value.sessionStartOverlay
            assertTrue(overlay is HomeSessionStartOverlayState.Start)
            assertEquals(
                3,
                (overlay as HomeSessionStartOverlayState.Start).remainingTicketCount,
            )
        }

    @Test
    fun `세션 확인 시 ENDED에 잔여 이용권이 0이면 NoTickets 오버레이가 세팅된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult =
                        Result.success(resumeStatus(InterviewResumeState.Ended)),
                )
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult =
                                Result.success(
                                    sampleUserProfile.copy(remainingTicketCount = 0),
                                ),
                        ),
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 42L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            assertTrue(
                viewModel.state.value.sessionStartOverlay is HomeSessionStartOverlayState.NoTickets,
            )
        }

    @Test
    fun `세션 확인 시 resumeState가 미정의 값이면 오버레이가 변하지 않는다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult =
                        Result.success(
                            resumeStatus(InterviewResumeState.Unknown("UNKNOWN_RAW")),
                        ),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 42L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            assertNull(viewModel.state.value.sessionStartOverlay)
        }

    @Test
    fun `세션 확인 실패가 NetworkUnavailableException이면 ShowNetworkErrorAndExit가 발행된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult =
                        Result.failure(
                            NetworkUnavailableException(errCode = "NETWORK_UNAVAILABLE"),
                        ),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 42L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            val globalEvents = mutableListOf<GlobalAppEvent>()
            val globalJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    GlobalErrorHandler.events.collect { globalEvents.add(it.event) }
                }

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            assertEquals(GlobalAppEvent.ShowNetworkErrorAndExit, globalEvents.firstOrNull())
            globalJob.cancel()
        }

    @Test
    fun `세션 확인 실패가 ServerException이면 ShowServerErrorAndExit가 발행된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult = Result.failure(ServerException(errCode = "SERVER")),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 42L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            val globalEvents = mutableListOf<GlobalAppEvent>()
            val globalJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    GlobalErrorHandler.events.collect { globalEvents.add(it.event) }
                }

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            assertEquals(GlobalAppEvent.ShowServerErrorAndExit, globalEvents.firstOrNull())
            globalJob.cancel()
        }

    @Test
    fun `세션 확인 실패가 기타 예외이면 ShowUnknownError가 발행된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult = Result.failure(IllegalStateException("unknown")),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 42L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            val globalEvents = mutableListOf<GlobalAppEvent>()
            val globalJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    GlobalErrorHandler.events.collect { globalEvents.add(it.event) }
                }

            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()

            assertEquals(GlobalAppEvent.ShowUnknownError, globalEvents.firstOrNull())
            globalJob.cancel()
        }

    // ---- ClickSessionResume (이어서 진행) ----

    @Test
    fun `ClickSessionResume은 GoToInterviewRequested를 발행하고 오버레이를 닫는다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            val effects = mutableListOf<HomeEffect>()
            val job = launch { viewModel.effect.collect(effects::add) }

            viewModel.onIntent(HomeIntent.ClickSessionResume)
            advanceUntilIdle()

            assertTrue(effects.contains(HomeEffect.GoToInterviewRequested))
            assertNull(viewModel.state.value.sessionStartOverlay)
            job.cancel()
        }

    // ---- ClickSessionStart 남은 시간 -> 남은 질문 개수 규칙 ----

    @Test
    fun `InProgress 오버레이의 남은 질문 개수는 남은 시간 규칙대로 계산된다`() =
        runViewModelTest {
            val cases =
                listOf(
                    720_000L to 4, // 갓 진입, 12분 그대로 남음
                    421_000L to 4, // 7분 초과
                    420_000L to 3, // 7분 경계(포함)
                    300_000L to 3, // 5분 경계(포함)
                    299_000L to 2, // 5분 미만
                    180_000L to 2, // 3분 경계(포함)
                    179_000L to 1, // 3분 미만
                    0L to 1, // 하드캡 도달
                )

            cases.forEach { (remainingMillis, expected) ->
                val elapsedMillis = InterviewTimeCalculator.HARD_CAP_MILLIS - remainingMillis
                val interviewRepo =
                    FakeInterviewRepository(
                        resumeResult = Result.success(resumeStatus(InterviewResumeState.Resumable)),
                    )
                val viewModel =
                    createViewModel(
                        interviewRepository = interviewRepo,
                        progress =
                            progressWithElapsed(
                                sessionId = 1L,
                                elapsedMillis = elapsedMillis,
                            ),
                    )
                viewModel.onIntent(HomeIntent.Load)
                advanceUntilIdle()

                viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
                advanceUntilIdle()

                val overlay = viewModel.state.value.sessionStartOverlay
                assertTrue(overlay is HomeSessionStartOverlayState.InProgress)
                assertEquals(
                    expected,
                    (overlay as HomeSessionStartOverlayState.InProgress).remainingQuestionCount,
                )
            }
        }

    // ---- ClickSessionStart - "처음부터 시작" 확인 단계 + 세션 중단 ----

    @Test
    fun `InProgress에서 ClickSessionStart는 확인 오버레이로만 전환하고 중단 API를 호출하지 않는다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult = Result.success(resumeStatus(InterviewResumeState.Resumable)),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 777L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()
            val overlayBeforeClick = viewModel.state.value.sessionStartOverlay
            check(overlayBeforeClick is HomeSessionStartOverlayState.InProgress)

            viewModel.onIntent(HomeIntent.ClickSessionStart)

            assertEquals(
                HomeSessionStartOverlayState.ConfirmRestart,
                viewModel.state.value.sessionStartOverlay,
            )
            assertTrue(interviewRepo.abandonCalls.isEmpty())
        }

    @Test
    fun `ConfirmRestart에서 ClickSessionStart는 기존 세션을 중단하고 온보딩으로 이동한다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult = Result.success(resumeStatus(InterviewResumeState.Resumable)),
                    abandonResult = Result.success(sampleAbandon(sessionId = 777L)),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 777L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ClickSessionStart) // InProgress -> ConfirmRestart
            val effects = mutableListOf<HomeEffect>()
            val job = launch { viewModel.effect.collect(effects::add) }

            viewModel.onIntent(HomeIntent.ClickSessionStart) // ConfirmRestart 확정
            advanceUntilIdle()

            assertEquals(
                listOf(777L to InterviewAbandonRequestCause.UserExit),
                interviewRepo.abandonCalls,
            )
            assertNull(viewModel.state.value.sessionStartOverlay)
            assertFalse(viewModel.state.value.isLoading)
            assertTrue(effects.contains(HomeEffect.GoToOnboardingInterviewRequested))
            job.cancel()
        }

    @Test
    fun `이미 종료된 세션의 중단 확정은 중복 성공으로 보고 온보딩으로 이동한다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult = Result.success(resumeStatus(InterviewResumeState.Resumable)),
                    abandonResult =
                        Result.failure(
                            InterviewSessionAlreadyEndedException(
                                errCode = "SESSION_ALREADY_ENDED",
                                message = "이미 종료된 세션이에요.",
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 777L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ClickSessionStart)
            val effects = mutableListOf<HomeEffect>()
            val job = launch { viewModel.effect.collect(effects::add) }

            viewModel.onIntent(HomeIntent.ClickSessionStart)
            advanceUntilIdle()

            assertTrue(effects.contains(HomeEffect.GoToOnboardingInterviewRequested))
            assertNull(viewModel.state.value.sessionStartOverlay)
            job.cancel()
        }

    @Test
    fun `중단 확정이 실패하면 전역 오류로 처리되고 온보딩으로 이동하지 않는다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    resumeResult = Result.success(resumeStatus(InterviewResumeState.Resumable)),
                    abandonResult = Result.failure(IllegalStateException("중단 실패")),
                )
            val viewModel =
                createViewModel(
                    interviewRepository = interviewRepo,
                    progress = sampleProgress(sessionId = 777L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ClickSessionStart)
            val effects = mutableListOf<HomeEffect>()
            val globalEvents = mutableListOf<GlobalAppEvent>()
            val effectJob = launch { viewModel.effect.collect(effects::add) }
            val globalJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    GlobalErrorHandler.events.collect { globalEvents.add(it.event) }
                }

            viewModel.onIntent(HomeIntent.ClickSessionStart)
            advanceUntilIdle()

            assertTrue(effects.none { it is HomeEffect.GoToOnboardingInterviewRequested })
            assertEquals(GlobalAppEvent.ShowUnknownError, globalEvents.firstOrNull())
            assertFalse(viewModel.state.value.isLoading)
            effectJob.cancel()
            globalJob.cancel()
        }

    // ---- 테스트 유틸 ----

    @Test
    fun `닫은 세션 오버레이는 늦게 도착한 재개 조회 결과로 다시 열리지 않는다`() =
        runViewModelTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                FakeInterviewRepository(
                    resumeResult = Result.success(resumeStatus(InterviewResumeState.Resumable)),
                    resumeGate = gate,
                )
            val viewModel =
                createViewModel(
                    interviewRepository = repository,
                    progress = sampleProgress(sessionId = 777L),
                )
            viewModel.onIntent(HomeIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(HomeIntent.ReportSheetCollapsed)
            advanceUntilIdle()
            assertEquals(listOf(777L), repository.resumeSessionIds)

            viewModel.onIntent(HomeIntent.ClickSessionOverlayDismiss)
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(viewModel.state.value.sessionStartOverlay)
        }

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

    private fun createViewModel(
        userRepository: UserRepository = FakeUserRepository(),
        interviewRepository: InterviewRepository = FakeInterviewRepository(),
        progress: InterviewProgress? = null,
    ): HomeViewModel {
        val localRepository = FakeInterviewLocalRepository(progress = progress)
        val clock = FakeInterviewClock()
        val calculator = InterviewTimeCalculator()
        return HomeViewModel(
            checkUserProfileUseCase = CheckUserProfileUseCase(userRepository),
            getInterviewReportListUseCase = GetInterviewReportListUseCase(interviewRepository),
            getInterviewResumeUseCase = GetInterviewResumeUseCase(interviewRepository),
            getInterviewProgressUseCase = GetInterviewProgressUseCase(localRepository),
            getInterviewElapsedTimeUseCase =
                GetInterviewElapsedTimeUseCase(localRepository, clock, calculator),
            abandonInterviewUseCase = AbandonInterviewUseCase(interviewRepository),
            retainInterviewSessionForCleanupUseCase =
                RetainInterviewSessionForCleanupUseCase(localRepository),
        )
    }

    /** [InterviewTimeCalculator]가 항상 같은 시각을 기준으로 delta 0을 계산하게 하는 고정 시계. */
    private class FakeInterviewClock : InterviewClock {
        override fun currentEpochMillis(): Long = FIXED_NOW_MILLIS

        override fun elapsedRealtimeMillis(): Long = FIXED_NOW_MILLIS
    }

    private fun reportItem(id: Long): InterviewReportListItem =
        InterviewReportListItem(
            sessionId = id,
            jobType = "ANDROID",
            jobTypeLabel = "Android",
            careerYears = 3,
            interviewedAt = "2026-08-11T10:00:00",
            portfolioFileName = null,
            portfolioDeleted = false,
            jdUrl = null,
            reportStatus = InterviewReportStatus.READY,
            feedbackAvailable = true,
            title = "샘플",
        )

    private fun resumeStatus(resumeState: InterviewResumeState): InterviewResumeStatus =
        InterviewResumeStatus(
            resumeState = resumeState,
            startedAt = null,
            elapsedSeconds = null,
            status = null,
        )

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

        /** [FakeInterviewClock]과 짝을 맞춰 항상 delta 0(=계산된 elapsed 그대로)이 나오는 시각. */
        const val FIXED_NOW_MILLIS = 1_000_000_000L

        fun sampleProgress(sessionId: Long): InterviewProgress =
            InterviewProgress(
                sessionId = sessionId,
                retentionDeadlineEpochMillis = 0L,
                retentionRemainingAtCheckpointMillis = 0L,
                retentionCheckpointElapsedRealtimeMillis = null,
                timerStartedAtEpochMillis = null,
                elapsedAtCheckpointMillis = null,
                checkpointedAtEpochMillis = null,
                elapsedCheckpointElapsedRealtimeMillis = null,
            )

        /**
         * [FakeInterviewClock]의 고정 시각을 체크포인트로 써서, [GetInterviewElapsedTimeUseCase]가
         * 정확히 [elapsedMillis]를 반환하도록 만든 진행 상태.
         */
        fun progressWithElapsed(
            sessionId: Long,
            elapsedMillis: Long,
        ): InterviewProgress =
            sampleProgress(sessionId).copy(
                elapsedAtCheckpointMillis = elapsedMillis,
                checkpointedAtEpochMillis = FIXED_NOW_MILLIS,
                elapsedCheckpointElapsedRealtimeMillis = FIXED_NOW_MILLIS,
            )

        fun sampleAbandon(sessionId: Long): InterviewAbandon =
            InterviewAbandon(
                sessionId = sessionId,
                status = InterviewTerminalStatus.Abandoned,
                abandonCause = InterviewAbandonCause.UserExit,
                endedAt = "2026-08-12T00:00:00",
                ticketOutcome = InterviewTicketOutcome.Committed,
                reportGenerating = false,
            )
    }

    private class FakeUserRepository(
        private val profileResult: Result<UserProfile> = Result.success(sampleUserProfile),
        private val profileGate: CompletableDeferred<Unit>? = null,
    ) : UserRepository {
        override suspend fun getUserProfile(): UserProfile {
            profileGate?.await()
            return profileResult.getOrThrow()
        }

        override suspend fun updateUserProfile(update: UserProfileUpdate) = Unit

        override suspend fun withdraw() = Unit

        override suspend fun getJobList() = error("사용하지 않음")
    }

    private class FakeInterviewRepository(
        var reportListResult: Result<InterviewReportList> =
            Result.success(InterviewReportList(reports = emptyList())),
        private val resumeResult: Result<InterviewResumeStatus>? = null,
        private val abandonResult: Result<InterviewAbandon>? = null,
        private val resumeGate: CompletableDeferred<Unit>? = null,
    ) : InterviewRepository {
        var getReportListCallCount = 0
            private set
        val resumeSessionIds = mutableListOf<Long>()
        val abandonCalls = mutableListOf<Pair<Long, InterviewAbandonRequestCause>>()

        override suspend fun validateJdUrl(jdUrl: String): JdValidationResult = error("사용하지 않음")

        override suspend fun createInterviewSession(
            request: InterviewSessionRequest,
        ): InterviewSessionResult = error("사용하지 않음")

        override suspend fun getInterviewSession(sessionId: Long): InterviewSessionStatus =
            error("사용하지 않음")

        override suspend fun getInterviewSessionStatus(sessionId: Long): InterviewSessionStatus =
            error("사용하지 않음")

        override suspend fun getReportList(): InterviewReportList {
            getReportListCallCount += 1
            return reportListResult.getOrThrow()
        }

        override suspend fun submitAnswer(
            command: SubmitInterviewAnswerCommand,
        ): SubmitAnswerResult = error("사용하지 않음")

        override fun getAudioStreamUrl(
            sessionId: Long,
            questionId: Long,
        ): String = error("사용하지 않음")

        override suspend fun uploadVideo(command: UploadInterviewVideoCommand) = error("사용하지 않음")

        override suspend fun getResume(sessionId: Long): InterviewResumeStatus {
            resumeSessionIds += sessionId
            resumeGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return resumeResult?.getOrThrow() ?: error("resumeResult가 세팅되지 않았습니다")
        }

        override suspend fun confirmResume(sessionId: Long): InterviewResumeConfirm =
            error("사용하지 않음")

        override suspend fun abandon(
            sessionId: Long,
            cause: InterviewAbandonRequestCause,
        ): InterviewAbandon {
            abandonCalls += sessionId to cause
            return abandonResult?.getOrThrow() ?: error("abandonResult가 세팅되지 않았습니다")
        }

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

    private class FakeInterviewLocalRepository(
        private val progress: InterviewProgress? = null,
    ) : InterviewLocalRepository {
        override suspend fun getProgress(): InterviewProgress? = progress

        override suspend fun saveProgress(progress: InterviewProgress) = Unit

        override suspend fun updateProgress(
            transform: (InterviewProgress) -> InterviewProgress,
        ): InterviewProgress? = null

        override suspend fun clearProgress() = Unit

        // RetainInterviewSessionForCleanupUseCase가 "매니페스트 없음 -> clearProgress" 분기를
        // 타도록 null을 반환한다.
        override suspend fun getManifest(sessionId: Long) = null

        override suspend fun getUploadManifest(uploadTaskId: String) = error("사용하지 않음")

        override suspend fun saveManifest(
            manifest: com.dminus14.app.domain.model.InterviewMediaManifest,
        ) = Unit

        override suspend fun createMediaFile(
            sessionId: Long,
            type: com.dminus14.app.domain.model.InterviewMediaSegmentType,
            extension: String,
        ) = error("사용하지 않음")

        override suspend fun createUploadMediaFile(
            uploadTaskId: String,
            extension: String,
        ) = error("사용하지 않음")

        override suspend fun deleteMediaFile(
            ref: com.dminus14.app.domain.model.InterviewMediaFileRef,
        ) = Unit

        override suspend fun handoffUploadTask(
            task: com.dminus14.app.domain.model.InterviewUploadTask,
        ) = Unit

        override suspend fun getUploadTask(uploadTaskId: String) = error("사용하지 않음")

        override suspend fun saveUploadTask(
            task: com.dminus14.app.domain.model.InterviewUploadTask,
        ) = Unit

        override suspend fun getUploadTasks() = error("사용하지 않음")

        override suspend fun deleteUploadTask(uploadTaskId: String) = Unit

        override suspend fun deleteSession(sessionId: Long) = Unit

        override suspend fun clearAll() = Unit

        override suspend fun isCleanupPending() = false

        override suspend fun setCleanupPending(isPending: Boolean) = Unit
    }
}
