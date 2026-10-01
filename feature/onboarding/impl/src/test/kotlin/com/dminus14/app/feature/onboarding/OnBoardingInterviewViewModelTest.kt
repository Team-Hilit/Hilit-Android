package com.dminus14.app.feature.onboarding

import android.content.Context
import android.net.Uri
import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.pdf.PdfInvalidReason
import com.dminus14.app.core.common.pdf.PdfValidationResult
import com.dminus14.app.core.common.pdf.validatePdf
import com.dminus14.app.domain.exception.AccountSuspendedException
import com.dminus14.app.domain.exception.FreeTextNotRelevantException
import com.dminus14.app.domain.exception.InvalidJdUrlException
import com.dminus14.app.domain.exception.JdValidationLimitExceededException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.model.InterviewAbandon
import com.dminus14.app.domain.model.InterviewAbandonRequestCause
import com.dminus14.app.domain.model.InterviewReport
import com.dminus14.app.domain.model.InterviewReportList
import com.dminus14.app.domain.model.InterviewResumeConfirm
import com.dminus14.app.domain.model.InterviewResumeStatus
import com.dminus14.app.domain.model.InterviewSessionRequest
import com.dminus14.app.domain.model.InterviewSessionResult
import com.dminus14.app.domain.model.InterviewSessionStatus
import com.dminus14.app.domain.model.InterviewSessionStatusType
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
import com.dminus14.app.domain.repository.InterviewRepository
import com.dminus14.app.domain.repository.PortfolioRepository
import com.dminus14.app.domain.repository.UserRepository
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.DeletePortfolioUseCase
import com.dminus14.app.domain.usecase.GetInterviewSessionUseCase
import com.dminus14.app.domain.usecase.GetPortfolioOverviewUseCase
import com.dminus14.app.domain.usecase.GetPortfolioStatusUseCase
import com.dminus14.app.domain.usecase.MakeInterviewSessionUseCase
import com.dminus14.app.domain.usecase.SaveInterviewSessionProgressUseCase
import com.dminus14.app.domain.usecase.UploadPortfolioUseCase
import com.dminus14.app.domain.usecase.ValidateJdUrlUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
@Suppress("LargeClass")
class OnBoardingInterviewViewModelTest {
    private lateinit var ioTestDispatcher: TestDispatcher

    // ---- load() ----

    @Test
    fun `Load 시 checkUserProfile가 실패하면 errorMessage에 예외 메시지가 반영된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    userRepository =
                        FakeUserRepository(
                            profileResult = Result.failure(IllegalStateException("프로필 실패")),
                        ),
                )

            viewModel.onIntent(OnBoardingInterviewIntent.Load)
            advanceUntilIdle()

            assertEquals("프로필 실패", viewModel.state.value.errorMessage)
        }

    @Test
    fun `Load 시 getPortfolioOverview가 실패하면 errorMessage로 노출된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(
                            overviewResult = Result.failure(IllegalStateException("오버뷰 실패")),
                        ),
                )

            viewModel.onIntent(OnBoardingInterviewIntent.Load)
            advanceUntilIdle()

            assertEquals("오버뷰 실패", viewModel.state.value.errorMessage)
        }

    @Test
    fun `Load 시 기존 포트폴리오가 READY면 파일명과 진행률 100이 반영된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(overviewResult = Result.success(readyOverview())),
                )

            viewModel.onIntent(OnBoardingInterviewIntent.Load)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("이력서.pdf", state.portfolioFileName)
            assertEquals(PORTFOLIO_PROGRESS_COMPLETE, state.portfolioUploadProgress)
        }

    @Test
    fun `Load 시 기존 포트폴리오가 READY가 아니면 파일명과 진행률이 반영되지 않는다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(
                            overviewResult =
                                Result.success(
                                    readyOverview().copy(
                                        portfolio =
                                            readyOverview().portfolio!!.copy(
                                                status = PortfolioStatus.PROCESSING,
                                            ),
                                    ),
                                ),
                        ),
                )

            viewModel.onIntent(OnBoardingInterviewIntent.Load)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.portfolioFileName)
            assertEquals(0, state.portfolioUploadProgress)
        }

    @Test
    fun `Load 완료 시점에 Portfolio 스텝이면 기존 포폴 안내 모달을 재시도한다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(
                            overviewResult = Result.success(existingOverview()),
                        ),
                )
            // load 이전에 Portfolio 스텝으로 이동시켜, load 완료 시 재시도 분기를 태운다.
            viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip)

            viewModel.onIntent(OnBoardingInterviewIntent.Load)
            advanceUntilIdle()

            assertEquals(
                ExistingPortfolioModalPhase.ConfirmContinue,
                viewModel.state.value.existingPortfolioModalPhase,
            )
        }

    // ---- JobDescriptionLinkChange (포맷 검사) ----

    @Test
    fun `링크가 비어 있으면 jdLinkStatus는 Idle이고 subText가 비워진다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionLinkChange(""))

            val state = viewModel.state.value
            assertEquals(JdLinkStatus.Idle, state.jdLinkStatus)
            assertEquals("", state.jdLinkSubText)
        }

    @Test
    fun `https 스킴으로 시작하지 않으면 Invalid와 포맷 안내 문구가 세팅된다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionLinkChange("http://jd.com"))

            val state = viewModel.state.value
            assertEquals(JdLinkStatus.Invalid, state.jdLinkStatus)
            assertEquals(MESSAGE_LINK_FORMAT, state.jdLinkSubText)
        }

    @Test
    fun `https 스킴을 입력 중이면 포맷 오류가 아니라 Idle로 둔다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            // "https://"까지만 입력한 상태는 스킴 입력 중으로 보고 포맷 오류로 취급하지 않는다.
            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionLinkChange("https://"))

            val state = viewModel.state.value
            assertEquals(JdLinkStatus.Idle, state.jdLinkStatus)
            assertEquals("", state.jdLinkSubText)
        }

    @Test
    fun `완전한 URL 입력만으로는 검증 요청이나 화면 이동이 발생하지 않는다`() =
        runViewModelTest {
            val interviewRepo = FakeInterviewRepository()
            val viewModel = createViewModel(interviewRepository = interviewRepo)

            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )

            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(JdLinkStatus.Idle, state.jdLinkStatus)
            assertEquals("", state.jdLinkSubText)
            assertTrue(state.isContinueEnabled())
            assertTrue(interviewRepo.validatedUrls.isEmpty())
            assertEquals(OnBoardingInterviewStep.JobDescription, state.step)
        }

    // ---- ClickContinue (JD 링크 검증) ----

    @Test
    fun `계속하기에서 검증에 성공하면 Valid로 바뀌고 다음 스텝으로 전진한다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            validateResult = Result.success(jdValidation(valid = true)),
                        ),
                )

            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )
            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(JdLinkStatus.Valid, state.jdLinkStatus)
            assertEquals(OnBoardingInterviewStep.Portfolio, state.step)
        }

    @Test
    fun `검증 결과 valid가 false면 Invalid와 서버 메시지가 노출된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            validateResult =
                                Result.success(
                                    jdValidation(valid = false, message = "공고를 읽지 못했어요"),
                                ),
                        ),
                )

            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )
            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(JdLinkStatus.Invalid, state.jdLinkStatus)
            assertEquals("공고를 읽지 못했어요", state.jdLinkSubText)
            assertEquals(OnBoardingInterviewStep.JobDescription, state.step)
        }

    @Test
    fun `검증 실패가 JdValidationLimitExceededException이면 하루 5회 제한 고정 문구가 노출된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            validateResult =
                                Result.failure(
                                    JdValidationLimitExceededException(
                                        errCode = "JD_VALIDATION_LIMIT_EXCEEDED",
                                        message = "무시되는 메시지",
                                    ),
                                ),
                        ),
                )

            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )
            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(JdLinkStatus.Invalid, state.jdLinkStatus)
            assertEquals(MESSAGE_LINK_RATE_LIMIT, state.jdLinkSubText)
        }

    @Test
    fun `검증 실패가 잘못된 JD 링크이면 서버 안내 문구를 입력 필드에 표시한다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            validateResult =
                                Result.failure(
                                    InvalidJdUrlException(
                                        errCode = "INVALID_JD_URL",
                                        message = "공고 링크를 확인해 주세요",
                                    ),
                                ),
                        ),
                )
            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            advanceUntilIdle()

            assertEquals(JdLinkStatus.Invalid, viewModel.state.value.jdLinkStatus)
            assertEquals("공고 링크를 확인해 주세요", viewModel.state.value.jdLinkSubText)
            assertEquals(OnBoardingInterviewStep.JobDescription, viewModel.state.value.step)
        }

    @Test
    fun `검증 중 알 수 없는 오류가 발생하면 전역 오류 이벤트가 발행된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            validateResult = Result.failure(IllegalStateException("네트워크 오류")),
                        ),
                )
            val globalEvent =
                async(start = CoroutineStart.UNDISPATCHED) { GlobalErrorHandler.events.first() }

            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )
            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            advanceUntilIdle()

            assertEquals(GlobalAppEvent.ShowUnknownError, globalEvent.await().event)
            assertEquals(JdLinkStatus.Idle, viewModel.state.value.jdLinkStatus)
            assertEquals(OnBoardingInterviewStep.JobDescription, viewModel.state.value.step)
        }

    @Test
    fun `검증 중 네트워크 오류가 발생하면 전역 네트워크 오류 이벤트가 발행된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            validateResult =
                                Result.failure(NetworkUnavailableException(errCode = "NETWORK")),
                        ),
                )
            val globalEvent =
                async(start = CoroutineStart.UNDISPATCHED) { GlobalErrorHandler.events.first() }
            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            advanceUntilIdle()

            assertEquals(GlobalAppEvent.ShowNetworkErrorAndExit, globalEvent.await().event)
            assertEquals(OnBoardingInterviewStep.JobDescription, viewModel.state.value.step)
        }

    @Test
    fun `검증 중 서버 오류가 발생하면 전역 서버 오류 이벤트가 발행된다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            validateResult = Result.failure(ServerException(errCode = "SERVER")),
                        ),
                )
            val globalEvent =
                async(start = CoroutineStart.UNDISPATCHED) { GlobalErrorHandler.events.first() }
            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            advanceUntilIdle()

            assertEquals(GlobalAppEvent.ShowServerErrorAndExit, globalEvent.await().event)
            assertEquals(OnBoardingInterviewStep.JobDescription, viewModel.state.value.step)
        }

    @Test
    fun `링크가 연속으로 바뀌어도 계속하기 전에는 요청하지 않고 마지막 값만 검증한다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(validateResult = Result.success(jdValidation(valid = true)))
            val viewModel = createViewModel(interviewRepository = interviewRepo)

            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/a"),
            )
            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/ab"),
            )
            advanceUntilIdle()

            assertTrue(interviewRepo.validatedUrls.isEmpty())
            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            advanceUntilIdle()

            assertEquals(listOf("https://jd.com/ab"), interviewRepo.validatedUrls)
        }

    @Test
    fun `검증 중 계속하기를 다시 눌러도 요청은 한 번만 전송된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    validateResult = Result.success(jdValidation(valid = true)),
                    validationDelayMs = 100,
                )
            val viewModel = createViewModel(interviewRepository = interviewRepo)
            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            assertEquals(JdLinkStatus.Validating, viewModel.state.value.jdLinkStatus)
            assertFalse(viewModel.state.value.isContinueEnabled())
            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            advanceUntilIdle()

            assertEquals(listOf("https://jd.com/1"), interviewRepo.validatedUrls)
            assertEquals(OnBoardingInterviewStep.Portfolio, viewModel.state.value.step)
        }

    @Test
    fun `검증 중 건너뛰면 늦게 도착한 결과가 다음 스텝으로 이동시키지 않는다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    validateResult = Result.success(jdValidation(valid = true)),
                    validationDelayMs = 100,
                )
            val viewModel = createViewModel(interviewRepository = interviewRepo)
            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )
            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)

            viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip)
            advanceUntilIdle()

            assertEquals(OnBoardingInterviewStep.Portfolio, viewModel.state.value.step)
            assertEquals(JdLinkStatus.Idle, viewModel.state.value.jdLinkStatus)
        }

    @Test
    fun `형식이 잘못된 링크는 계속하기를 눌러도 API를 요청하지 않는다`() =
        runViewModelTest {
            val interviewRepo = FakeInterviewRepository()
            val viewModel = createViewModel(interviewRepository = interviewRepo)
            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionLinkChange("http://jd.com"))

            assertTrue(viewModel.state.value.isContinueEnabled())
            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)

            assertTrue(interviewRepo.validatedUrls.isEmpty())
            assertEquals(JdLinkStatus.Invalid, viewModel.state.value.jdLinkStatus)
            assertEquals(OnBoardingInterviewStep.JobDescription, viewModel.state.value.step)
        }

    // ---- JobDescriptionTextChange / Text 탭 제출 ----

    @Test
    fun `텍스트 입력이 3000자를 넘으면 잘려서 반영되고 에러가 초기화된다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionTextChange("가".repeat(3500)))

            val state = viewModel.state.value
            assertEquals(3000, state.jobDescriptionText.length)
            assertNull(state.jdTextError)
        }

    @Test
    fun `Text 탭에서 200자 미만이면 too-short 에러가 세팅되고 전진하지 않는다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionTabChange(1))
            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionTextChange("가".repeat(199)))

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)

            val state = viewModel.state.value
            assertEquals(MESSAGE_TEXT_TOO_SHORT, state.jdTextError)
            assertEquals(OnBoardingInterviewStep.JobDescription, state.step)
        }

    @Test
    fun `Text 탭에서 200자 이상이면 다음 스텝으로 전진한다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionTabChange(1))
            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionTextChange("가".repeat(200)))

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)

            assertEquals(OnBoardingInterviewStep.Portfolio, viewModel.state.value.step)
        }

    // ---- JobDescriptionTabChange ----

    @Test
    fun `유효한 인덱스로 탭을 바꾸면 해당 탭으로 전환된다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionTabChange(1))

            assertEquals(JobDescriptionTab.Text, viewModel.state.value.jobDescriptionTab)
        }

    @Test
    fun `범위를 벗어난 인덱스면 기존 탭이 유지된다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            viewModel.onIntent(OnBoardingInterviewIntent.JobDescriptionTabChange(9))

            assertEquals(JobDescriptionTab.Link, viewModel.state.value.jobDescriptionTab)
        }

    // ---- Link 탭 제출 (submitJobDescription) ----

    @Test
    fun `Link 탭에서 링크가 비어 있으면 건너뛰고 다음 스텝으로 전진한다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)

            assertEquals(OnBoardingInterviewStep.Portfolio, viewModel.state.value.step)
        }

    @Test
    fun `Link 탭에서 검증 결과가 실패이면 계속하기를 눌러도 전진하지 않는다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    interviewRepository =
                        FakeInterviewRepository(
                            validateResult = Result.success(jdValidation(valid = false)),
                        ),
                )
            viewModel.onIntent(
                OnBoardingInterviewIntent.JobDescriptionLinkChange("https://jd.com/1"),
            )

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
            advanceUntilIdle()

            assertEquals(OnBoardingInterviewStep.JobDescription, viewModel.state.value.step)
        }

    // ---- Portfolio 필수 검증 (onContinueClick) ----

    @Test
    fun `Portfolio 스텝에서 준비된 포트폴리오가 없으면 필수 안내 에러가 노출된다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) // JobDescription -> Portfolio

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)

            val state = viewModel.state.value
            assertEquals(MESSAGE_PORTFOLIO_REQUIRED, state.portfolioErrorMessage)
            assertEquals(OnBoardingInterviewStep.Portfolio, state.step)
        }

    @Test
    fun `Portfolio 스텝에서 준비된 포트폴리오가 있으면 다음 스텝으로 전진한다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(overviewResult = Result.success(readyOverview())),
                )
            viewModel.onIntent(OnBoardingInterviewIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) // JobDescription -> Portfolio

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)

            assertEquals(OnBoardingInterviewStep.MainProject, viewModel.state.value.step)
        }

    // ---- 기존 포트폴리오 안내 모달 ----

    @Test
    fun `기존 포폴이 있고 교체·삭제가 모두 가능하면 ConfirmContinue 모달이 뜬다`() =
        runViewModelTest {
            val viewModel = viewModelWithExisting(replace = true, delete = true)

            viewModel.onIntent(OnBoardingInterviewIntent.PortfolioStepEntered)

            assertEquals(
                ExistingPortfolioModalPhase.ConfirmContinue,
                viewModel.state.value.existingPortfolioModalPhase,
            )
        }

    @Test
    fun `기존 포폴이 있고 교체·삭제가 불가하면 AutoDismissNotice가 뜬다`() =
        runViewModelTest {
            val viewModel = viewModelWithExisting(replace = false, delete = true)

            viewModel.onIntent(OnBoardingInterviewIntent.PortfolioStepEntered)

            assertEquals(
                ExistingPortfolioModalPhase.AutoDismissNotice,
                viewModel.state.value.existingPortfolioModalPhase,
            )
        }

    @Test
    fun `기존 포폴이 없으면 안내 모달이 뜨지 않는다`() =
        runViewModelTest {
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(overviewResult = Result.success(emptyOverview())),
                )
            viewModel.onIntent(OnBoardingInterviewIntent.Load)
            advanceUntilIdle()
            viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip)

            viewModel.onIntent(OnBoardingInterviewIntent.PortfolioStepEntered)

            assertEquals(
                ExistingPortfolioModalPhase.None,
                viewModel.state.value.existingPortfolioModalPhase,
            )
        }

    @Test
    fun `안내 모달은 세션당 1회만 노출된다`() =
        runViewModelTest {
            val viewModel = viewModelWithExisting(replace = true, delete = true)
            viewModel.onIntent(OnBoardingInterviewIntent.PortfolioStepEntered)
            viewModel.onIntent(OnBoardingInterviewIntent.ClickExistingPortfolioConfirm)

            viewModel.onIntent(OnBoardingInterviewIntent.PortfolioStepEntered)

            assertEquals(
                ExistingPortfolioModalPhase.None,
                viewModel.state.value.existingPortfolioModalPhase,
            )
        }

    @Test
    fun `ClickExistingPortfolioConfirm이면 모달이 None으로 닫힌다`() =
        runViewModelTest {
            val viewModel = viewModelWithExisting(replace = true, delete = true)
            viewModel.onIntent(OnBoardingInterviewIntent.PortfolioStepEntered)

            viewModel.onIntent(OnBoardingInterviewIntent.ClickExistingPortfolioConfirm)

            assertEquals(
                ExistingPortfolioModalPhase.None,
                viewModel.state.value.existingPortfolioModalPhase,
            )
        }

    @Test
    fun `AutoDismissNotice 상태에서 dismiss하면 모달을 닫고 다음 스텝으로 전진한다`() =
        runViewModelTest {
            val viewModel = viewModelWithExisting(replace = false, delete = false)
            viewModel.onIntent(OnBoardingInterviewIntent.PortfolioStepEntered)

            viewModel.onIntent(OnBoardingInterviewIntent.ClickAutoDismissNotice)

            val state = viewModel.state.value
            assertEquals(ExistingPortfolioModalPhase.None, state.existingPortfolioModalPhase)
            assertEquals(OnBoardingInterviewStep.MainProject, state.step)
        }

    @Test
    fun `AutoDismissNotice가 아닌 상태에서는 dismiss가 무시된다`() =
        runViewModelTest {
            val viewModel = viewModelWithExisting(replace = true, delete = true)
            viewModel.onIntent(OnBoardingInterviewIntent.PortfolioStepEntered) // ConfirmContinue

            viewModel.onIntent(OnBoardingInterviewIntent.ClickAutoDismissNotice)

            assertEquals(OnBoardingInterviewStep.Portfolio, viewModel.state.value.step)
        }

    // ---- MainProjectTextChange / submitMainProject ----

    @Test
    fun `집중 프로젝트 입력이 300자를 넘으면 잘려서 반영되고 에러가 초기화된다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            viewModel.onIntent(OnBoardingInterviewIntent.MainProjectTextChange("가".repeat(400)))

            val state = viewModel.state.value
            assertEquals(300, state.mainProjectText.length)
            assertNull(state.mainProjectError)
        }

    @Test
    fun `집중 프로젝트가 비어 있으면 제출 시 다음 스텝으로 전진한다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            moveToMainProject(viewModel)

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)

            assertEquals(OnBoardingInterviewStep.Preload, viewModel.state.value.step)
        }

    @Test
    fun `집중 프로젝트가 1자 이상 10자 미만이면 too-short 에러가 세팅되고 전진하지 않는다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            moveToMainProject(viewModel)
            viewModel.onIntent(OnBoardingInterviewIntent.MainProjectTextChange("짧은설명"))

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)

            val state = viewModel.state.value
            assertEquals(MESSAGE_FREETEXT_TOO_SHORT, state.mainProjectError)
            assertEquals(OnBoardingInterviewStep.MainProject, state.step)
        }

    @Test
    fun `집중 프로젝트가 10자 이상이면 다음 스텝으로 전진한다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            moveToMainProject(viewModel)
            viewModel.onIntent(
                OnBoardingInterviewIntent.MainProjectTextChange("열자이상충분한설명입니다"),
            )

            viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)

            assertEquals(OnBoardingInterviewStep.Preload, viewModel.state.value.step)
        }

    // ---- Preload / 세션 생성 ----

    @Test
    fun `필요한 프로필 정보나 포트폴리오가 없으면 안내 에러 후 세션을 만들지 않는다`() =
        runViewModelTest {
            val interviewRepo = FakeInterviewRepository()
            val viewModel = createViewModel(interviewRepository = interviewRepo)
            // load 없이 Preload로 진입해 jobRole/portfolioId 가드를 태운다.
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("필요한 정보를 불러오지 못했어요. 다시 시도해 주세요.", state.errorMessage)
            assertEquals(0, interviewRepo.createCallCount)
        }

    @Test
    fun `집중 프로젝트가 비어 있으면 세션 생성 요청의 freeText는 null로 전송된다`() =
        runViewModelTest {
            val interviewRepo = readySessionRepo()
            val viewModel = loadedForPreload(interviewRepo)
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            assertNull(interviewRepo.lastRequest?.freeText)
        }

    @Test
    fun `세션 생성 성공 시 basicInfo는 Completed 나머지는 InProgress로 전환된다`() =
        runViewModelTest {
            // 세션은 생성되지만 이후 폴링은 PROCESSING으로 멈춰 전환 시점 상태를 관찰한다.
            val interviewRepo =
                FakeInterviewRepository(
                    createResult = Result.success(sessionResult(sessionId = 11L)),
                    sessionStatusResults =
                        listOf(
                            Result.success(sessionStatus(InterviewSessionStatusType.PROCESSING)),
                        ),
                )
            val viewModel = loadedForPreload(interviewRepo)
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            // 폴링 첫 delay 이전까지만 진행시켜 InProgress 전환을 확인한다.

            val state = viewModel.state.value
            assertEquals(OnBoardingLoadingStepStatus.Completed, state.loadingBasicInfo)
            assertEquals(OnBoardingLoadingStepStatus.InProgress, state.loadingJd)
            assertEquals(OnBoardingLoadingStepStatus.InProgress, state.loadingPortfolio)
        }

    @Test
    fun `세션 폴링이 READY면 로딩이 모두 Completed되고 NavigateToResult Effect가 발행된다`() =
        runViewModelTest {
            val interviewRepo = readySessionRepo(sessionId = 77L)
            val viewModel = loadedForPreload(interviewRepo)
            val effects = mutableListOf<OnBoardingInterviewEffect>()
            val job = launch { viewModel.effect.collect(effects::add) }

            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(OnBoardingLoadingStepStatus.Completed, state.loadingBasicInfo)
            assertEquals(OnBoardingLoadingStepStatus.Completed, state.loadingJd)
            assertEquals(OnBoardingLoadingStepStatus.Completed, state.loadingPortfolio)
            assertEquals(
                listOf(OnBoardingInterviewEffect.NavigateToResult(77L)),
                effects,
            )
            job.cancel()
        }

    @Test
    fun `세션 폴링이 FAILED면 실패 안내 errorMessage가 노출된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    createResult = Result.success(sessionResult()),
                    sessionStatusResults =
                        listOf(
                            Result.success(sessionStatus(InterviewSessionStatusType.FAILED)),
                        ),
                )
            val viewModel = loadedForPreload(interviewRepo)
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            assertEquals("면접 준비에 실패했어요. 다시 시도해 주세요.", viewModel.state.value.errorMessage)
        }

    @Test
    fun `세션 폴링이 최대 횟수를 넘기면 지연 안내 errorMessage가 노출된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    createResult = Result.success(sessionResult()),
                    sessionStatusResults =
                        listOf(
                            Result.success(sessionStatus(InterviewSessionStatusType.PROCESSING)),
                        ),
                    repeatLastSessionStatus = true,
                )
            val viewModel = loadedForPreload(interviewRepo)
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            assertEquals(
                "면접 준비가 지연되고 있어요. 잠시 후 다시 시도해 주세요.",
                viewModel.state.value.errorMessage,
            )
        }

    @Test
    fun `세션 폴링 조회가 실패하면 예외 메시지로 노출되고 폴링이 중단된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    createResult = Result.success(sessionResult()),
                    sessionStatusResults =
                        listOf(
                            Result.failure(IllegalStateException("세션 조회 실패")),
                        ),
                )
            val viewModel = loadedForPreload(interviewRepo)
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            assertEquals("세션 조회 실패", viewModel.state.value.errorMessage)
        }

    // ---- 연관성 실패 처리 (handleSessionCreateFailure) ----

    @Test
    fun `연관성 실패가 아닌 기타 실패는 상단 errorMessage와 로딩 초기화로 처리된다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    createResult = Result.failure(IllegalStateException("세션 생성 실패")),
                )
            val viewModel = loadedForPreload(interviewRepo)
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("세션 생성 실패", state.errorMessage)
            assertEquals(OnBoardingLoadingStepStatus.Waiting, state.loadingBasicInfo)
        }

    @Test
    fun `계정 정지(ACCOUNT_SUSPENDED)면 인라인 에러 대신 이용 제한 안내로 이동한다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    createResult =
                        Result.failure(
                            AccountSuspendedException(
                                errCode = "ACCOUNT_SUSPENDED",
                                message = "비정상적인 이용 패턴이 반복 확인되어 면접 시작이 제한되었어요.",
                            ),
                        ),
                )
            val viewModel = loadedForPreload(interviewRepo)
            val effects = mutableListOf<OnBoardingInterviewEffect>()
            val job = launch { viewModel.effect.collect(effects::add) }

            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.errorMessage)
            assertEquals(OnBoardingLoadingStepStatus.Waiting, state.loadingBasicInfo)
            assertEquals(
                listOf(OnBoardingInterviewEffect.NavigateToSuspensionNotice),
                effects,
            )
            job.cancel()
        }

    @Test
    fun `연관성 실패 1회는 MainProject로 되돌리고 인라인 에러만 노출한다`() =
        runViewModelTest {
            val viewModel = loadedForPreload(relevanceFailRepo())
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(OnBoardingInterviewStep.MainProject, state.step)
            assertEquals(MESSAGE_FREETEXT_NOT_RELEVANT, state.mainProjectError)
            assertEquals(1, state.mainProjectRelevanceFailCount)
            assertFalse(state.showRelevanceFailDialog)
        }

    @Test
    fun `연관성 실패가 4회에 도달하면 재선택 다이얼로그가 함께 뜬다`() =
        runViewModelTest {
            val viewModel = loadedForPreload(relevanceFailRepo())
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle() // 1회
            repeat(3) {
                viewModel.onIntent(OnBoardingInterviewIntent.ClickContinue)
                advanceUntilIdle()
            } // 2, 3, 4회

            val state = viewModel.state.value
            assertEquals(4, state.mainProjectRelevanceFailCount)
            assertTrue(state.showRelevanceFailDialog)
        }

    @Test
    fun `ClickRelevanceRetryPortfolio면 다이얼로그를 닫고 Portfolio 스텝으로 되돌리며 카운트를 초기화한다`() =
        runViewModelTest {
            val viewModel = loadedForPreload(relevanceFailRepo())
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            viewModel.onIntent(OnBoardingInterviewIntent.ClickRelevanceRetryPortfolio)

            val state = viewModel.state.value
            assertEquals(OnBoardingInterviewStep.Portfolio, state.step)
            assertEquals(0, state.mainProjectRelevanceFailCount)
            assertFalse(state.showRelevanceFailDialog)
            assertNull(state.mainProjectError)
        }

    @Test
    fun `ClickRelevanceProceedWithoutMainProject면 freeText를 비우고 Preload를 다시 시작한다`() =
        runViewModelTest {
            val interviewRepo = readySessionRepo()
            val viewModel = loadedForPreload(interviewRepo)
            viewModel.onIntent(
                OnBoardingInterviewIntent.MainProjectTextChange("포트폴리오와 무관한 어떤 프로젝트 설명"),
            )

            viewModel.onIntent(OnBoardingInterviewIntent.ClickRelevanceProceedWithoutMainProject)
            advanceUntilIdle()

            assertEquals("", viewModel.state.value.mainProjectText)
            assertNull(interviewRepo.lastRequest?.freeText)
        }

    // ---- Preload 실패 확인 ----

    @Test
    fun `ClickPreloadFailureAcknowledged면 MainProject로 되돌리고 로딩을 Waiting으로 초기화한다`() =
        runViewModelTest {
            val interviewRepo =
                FakeInterviewRepository(
                    createResult = Result.failure(IllegalStateException("세션 생성 실패")),
                )
            val viewModel = loadedForPreload(interviewRepo)
            repeat(3) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
            advanceUntilIdle()

            viewModel.onIntent(OnBoardingInterviewIntent.ClickPreloadFailureAcknowledged)

            val state = viewModel.state.value
            assertEquals(OnBoardingInterviewStep.MainProject, state.step)
            assertNull(state.errorMessage)
            assertEquals(OnBoardingLoadingStepStatus.Waiting, state.loadingBasicInfo)
        }

    // ---- 포트폴리오 삭제 (removePortfolio) ----

    @Test
    fun `준비된 포트폴리오가 없으면 삭제가 시도되지 않는다`() =
        runViewModelTest {
            val portfolioRepo = FakePortfolioRepository()
            val viewModel = createViewModel(portfolioRepository = portfolioRepo)

            viewModel.onIntent(OnBoardingInterviewIntent.ClickPortfolioRemove)
            advanceUntilIdle()

            assertEquals(0, portfolioRepo.deleteCallCount)
        }

    @Test
    fun `삭제 성공 시 로컬 포폴 상태가 모두 초기화된다`() =
        runViewModelTest {
            val portfolioRepo =
                FakePortfolioRepository(
                    overviewResult = Result.success(readyOverview()),
                    deleteResult =
                        Result.success(
                            PortfolioDeleteResult("pf-1", "2026-08-11T10:00:00"),
                        ),
                )
            val viewModel = createViewModel(portfolioRepository = portfolioRepo)
            viewModel.onIntent(OnBoardingInterviewIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(OnBoardingInterviewIntent.ClickPortfolioRemove)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.portfolioFileName)
            assertFalse(state.isPortfolioProcessing)
            assertEquals(0, state.portfolioUploadProgress)
            assertFalse(state.hasShownExistingPortfolioNotice)
        }

    @Test
    fun `삭제 실패 시 processing이 해제되고 인라인 에러가 노출된다`() =
        runViewModelTest {
            val portfolioRepo =
                FakePortfolioRepository(
                    overviewResult = Result.success(readyOverview()),
                    deleteResult = Result.failure(IllegalStateException("삭제 실패")),
                )
            val viewModel = createViewModel(portfolioRepository = portfolioRepo)
            viewModel.onIntent(OnBoardingInterviewIntent.Load)
            advanceUntilIdle()

            viewModel.onIntent(OnBoardingInterviewIntent.ClickPortfolioRemove)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.isPortfolioProcessing)
            assertEquals("삭제 실패", state.portfolioErrorMessage)
        }

    // ---- 포트폴리오 업로드 (validatePdf/Uri는 mockk로 가짜 처리) ----

    @Test
    fun `파일 선택 시 파일명·processing·진행률 0이 즉시 세팅된다`() =
        runViewModelTest {
            mockPdf(PdfValidationResult.Valid(pageCount = 3))
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(
                            uploadResult = Result.success(uploadAccepted("pf-1")),
                            statusResults =
                                listOf(
                                    Result.success(portfolioStatus(PortfolioStatus.READY)),
                                ),
                        ),
                )

            viewModel.onIntent(portfolioSelected())

            val state = viewModel.state.value
            assertEquals("a.pdf", state.portfolioFileName)
            assertTrue(state.isPortfolioProcessing)
            assertEquals(0, state.portfolioUploadProgress)
            settleUpload()
        }

    @Test
    fun `PDF 검증이 사이즈 초과로 실패하면 사이즈 안내 문구로 실패 처리된다`() =
        runViewModelTest {
            assertPdfInvalidShowsMessage(PdfInvalidReason.INVALID_FILE_SIZE, MESSAGE_PDF_SIZE)
        }

    @Test
    fun `PDF 검증이 페이지 초과로 실패하면 페이지 안내 문구로 실패 처리된다`() =
        runViewModelTest {
            assertPdfInvalidShowsMessage(PdfInvalidReason.INVALID_PAGE_COUNT, MESSAGE_PDF_PAGE)
        }

    @Test
    fun `PDF 검증이 암호 걸림으로 실패하면 암호 안내 문구로 실패 처리된다`() =
        runViewModelTest {
            assertPdfInvalidShowsMessage(PdfInvalidReason.PASSWORD_REQUIRED, MESSAGE_PDF_PASSWORD)
        }

    @Test
    fun `PDF 검증이 손상·포맷 오류로 실패하면 손상 안내 문구로 실패 처리된다`() =
        runViewModelTest {
            assertPdfInvalidShowsMessage(PdfInvalidReason.INVALID_PDF_FORMAT, MESSAGE_PDF_CORRUPT)
        }

    @Test
    fun `업로드 요청이 실패하면 예외 메시지로 실패 처리된다`() =
        runViewModelTest {
            mockPdf(PdfValidationResult.Valid(pageCount = 3))
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(
                            uploadResult = Result.failure(IllegalStateException("업로드 실패")),
                        ),
                )

            viewModel.onIntent(portfolioSelected())
            settleUpload()

            val state = viewModel.state.value
            assertEquals("업로드 실패", state.portfolioErrorMessage)
            assertFalse(state.isPortfolioProcessing)
            assertNull(state.portfolioFileName)
            assertEquals(0, state.portfolioUploadProgress)
        }

    @Test
    fun `폴링이 READY면 진행률 100과 processing 해제로 완료 처리된다`() =
        runViewModelTest {
            mockPdf(PdfValidationResult.Valid(pageCount = 3))
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(
                            uploadResult = Result.success(uploadAccepted("pf-9")),
                            statusResults =
                                listOf(
                                    Result.success(portfolioStatus(PortfolioStatus.READY)),
                                ),
                        ),
                )

            viewModel.onIntent(portfolioSelected())
            settleUpload()

            val state = viewModel.state.value
            assertEquals(PORTFOLIO_PROGRESS_COMPLETE, state.portfolioUploadProgress)
            assertFalse(state.isPortfolioProcessing)
        }

    @Test
    fun `폴링이 FAILED_FILE이면 읽기 불가 안내 문구로 실패 처리된다`() =
        runViewModelTest {
            assertPollStatusShowsMessage(PortfolioStatus.FAILED_FILE, MESSAGE_PDF_UNREADABLE)
        }

    @Test
    fun `폴링이 FAILED_SYSTEM이면 시스템 실패 문구로 실패 처리된다`() =
        runViewModelTest {
            assertPollStatusShowsMessage(
                PortfolioStatus.FAILED_SYSTEM,
                MESSAGE_PORTFOLIO_SYSTEM_FAILED,
            )
        }

    @Test
    fun `폴링 대기 구간에서는 진행률이 10퍼센트씩 오르되 90퍼센트에서 캡되고 타임아웃 처리된다`() =
        runViewModelTest {
            mockPdf(PdfValidationResult.Valid(pageCount = 3))
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(
                            uploadResult = Result.success(uploadAccepted("pf-1")),
                            statusResults =
                                listOf(
                                    Result.success(portfolioStatus(PortfolioStatus.PROCESSING)),
                                ),
                            repeatLastStatus = true,
                        ),
                )
            val progresses = mutableListOf<Int>()
            val job =
                launch { viewModel.state.collect { progresses.add(it.portfolioUploadProgress) } }

            viewModel.onIntent(portfolioSelected())
            settleUpload()

            assertTrue("진행률이 90에 도달해야 한다", progresses.contains(90))
            assertEquals(90, progresses.filter { it in 1..99 }.maxOrNull())
            val state = viewModel.state.value
            assertEquals(0, state.portfolioUploadProgress)
            assertEquals(MESSAGE_PORTFOLIO_SYSTEM_FAILED, state.portfolioErrorMessage)
            job.cancel()
        }

    @Test
    fun `폴링 중 상태 조회가 실패하면 예외 메시지로 실패 처리되고 폴링이 중단된다`() =
        runViewModelTest {
            mockPdf(PdfValidationResult.Valid(pageCount = 3))
            val viewModel =
                createViewModel(
                    portfolioRepository =
                        FakePortfolioRepository(
                            uploadResult = Result.success(uploadAccepted("pf-1")),
                            statusResults =
                                listOf(
                                    Result.failure(IllegalStateException("상태 조회 실패")),
                                ),
                        ),
                )

            viewModel.onIntent(portfolioSelected())
            settleUpload()

            val state = viewModel.state.value
            assertEquals("상태 조회 실패", state.portfolioErrorMessage)
            assertFalse(state.isPortfolioProcessing)
        }

    // ---- 스텝 이동 / 종료 / 스킵 ----

    @Test
    fun `JobDescription에서 이전을 누르면 CloseRequested Effect가 발행된다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(OnBoardingInterviewIntent.ClickPrevious)

            assertEquals(OnBoardingInterviewEffect.CloseRequested, effect.await())
        }

    @Test
    fun `Portfolio에서 이전을 누르면 JobDescription으로 돌아간다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) // -> Portfolio

            viewModel.onIntent(OnBoardingInterviewIntent.ClickPrevious)

            assertEquals(OnBoardingInterviewStep.JobDescription, viewModel.state.value.step)
        }

    @Test
    fun `MainProject에서 이전을 누르면 Portfolio로 돌아간다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            repeat(2) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) } // -> MainProject

            viewModel.onIntent(OnBoardingInterviewIntent.ClickPrevious)

            assertEquals(OnBoardingInterviewStep.Portfolio, viewModel.state.value.step)
        }

    @Test
    fun `ClickClose면 CloseRequested Effect가 발행된다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(OnBoardingInterviewIntent.ClickClose)

            assertEquals(OnBoardingInterviewEffect.CloseRequested, effect.await())
        }

    @Test
    fun `ClickSkip이면 다음 스텝으로 전진한다`() =
        runViewModelTest {
            val viewModel = createViewModel()

            viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip)

            assertEquals(OnBoardingInterviewStep.Portfolio, viewModel.state.value.step)
        }

    @Test
    fun `ClickPortfolioUpload면 LaunchPortfolioPicker Effect가 발행된다`() =
        runViewModelTest {
            val viewModel = createViewModel()
            val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effect.first() }

            viewModel.onIntent(OnBoardingInterviewIntent.ClickPortfolioUpload)

            assertEquals(OnBoardingInterviewEffect.LaunchPortfolioPicker, effect.await())
        }

    // ---- 테스트 유틸 ----

    private fun runViewModelTest(block: suspend TestScope.() -> Unit) =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            // Main과 다른 디스패처 인스턴스를 써서 startPortfolioUpload의 withContext가
            // 실제로 suspend하게 한다. 같은 testScheduler를 공유하므로 advanceUntilIdle로
            // 결정적으로 진행시킬 수 있다.
            ioTestDispatcher = StandardTestDispatcher(testScheduler)
            try {
                block()
                // 관찰을 위해 일부러 진행시키지 않은 지연 코루틴을 Main이 유효한 동안 마저 비운다.
                // (남겨 두면 resetMain 이후 재개되며 다른 테스트를 오염시킨다.)
                advanceUntilIdle()
            } finally {
                unmockkAll()
                Dispatchers.resetMain()
            }
        }

    private fun createViewModel(
        userRepository: UserRepository = FakeUserRepository(),
        portfolioRepository: PortfolioRepository = FakePortfolioRepository(),
        interviewRepository: InterviewRepository = FakeInterviewRepository(),
    ): OnBoardingInterviewViewModel =
        OnBoardingInterviewViewModel(
            context = mockk(relaxed = true),
            checkUserProfile = CheckUserProfileUseCase(userRepository),
            getPortfolioOverview = GetPortfolioOverviewUseCase(portfolioRepository),
            uploadPortfolio = UploadPortfolioUseCase(portfolioRepository),
            getPortfolioStatus = GetPortfolioStatusUseCase(portfolioRepository),
            deletePortfolio = DeletePortfolioUseCase(portfolioRepository),
            validateJdUrl = ValidateJdUrlUseCase(interviewRepository),
            makeInterviewSession = MakeInterviewSessionUseCase(interviewRepository),
            getInterviewSession = GetInterviewSessionUseCase(interviewRepository),
            saveInterviewSessionProgress =
                SaveInterviewSessionProgressUseCase(
                    repository = mockk(relaxed = true),
                    clock = mockk(relaxed = true),
                ),
            // 실제 IO 스레드로 튀면 테스트 스케줄러가 완료 시점을 통제하지 못해 assertion과
            // 경합(flaky)한다. 같은 testScheduler를 공유하는 테스트 디스패처를 써서
            // 결정론적으로 진행시킨다.
            ioDispatcher = ioTestDispatcher,
        )

    /** validatePdf(Android 프레임워크 의존)와 Uri.fromFile을 가짜 값으로 대체한다. */
    private fun mockPdf(result: PdfValidationResult) {
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } returns mockk(relaxed = true)
        mockkStatic("com.dminus14.app.core.common.pdf.PdfValidationKt")
        every { validatePdf(any(), any()) } returns result
    }

    /**
     * 업로드 흐름의 IO hop이 testScheduler를 공유하는 [ioTestDispatcher]로 가므로
     * 가상 시간만 진행시켜도 결정적으로 완료까지 도달한다.
     */
    private fun TestScope.settleUpload() {
        advanceUntilIdle()
    }

    private fun TestScope.assertPdfInvalidShowsMessage(
        reason: PdfInvalidReason,
        message: String,
    ) {
        mockPdf(PdfValidationResult.Invalid(reason))
        val portfolioRepo = FakePortfolioRepository()
        val viewModel = createViewModel(portfolioRepository = portfolioRepo)

        viewModel.onIntent(portfolioSelected())
        settleUpload()

        val state = viewModel.state.value
        assertEquals(message, state.portfolioErrorMessage)
        assertFalse(state.isPortfolioProcessing)
        assertNull(state.portfolioFileName)
        assertEquals(0, portfolioRepo.uploadCallCount)
    }

    private fun TestScope.assertPollStatusShowsMessage(
        status: PortfolioStatus,
        message: String,
    ) {
        mockPdf(PdfValidationResult.Valid(pageCount = 3))
        val viewModel =
            createViewModel(
                portfolioRepository =
                    FakePortfolioRepository(
                        uploadResult = Result.success(uploadAccepted("pf-1")),
                        statusResults = listOf(Result.success(portfolioStatus(status))),
                    ),
            )

        viewModel.onIntent(portfolioSelected())
        settleUpload()

        val state = viewModel.state.value
        assertEquals(message, state.portfolioErrorMessage)
        assertFalse(state.isPortfolioProcessing)
    }

    private fun portfolioSelected(): OnBoardingInterviewIntent.PortfolioFileSelected =
        OnBoardingInterviewIntent.PortfolioFileSelected(file = File("a.pdf"), fileName = "a.pdf")

    private fun viewModelWithExisting(
        replace: Boolean,
        delete: Boolean,
    ): OnBoardingInterviewViewModel {
        val viewModel =
            createViewModel(
                portfolioRepository =
                    FakePortfolioRepository(
                        overviewResult =
                            Result.success(existingOverview(replace = replace, delete = delete)),
                    ),
            )
        // load로 existingPortfolioId를 채운 뒤 Portfolio 스텝으로 이동한다.
        viewModel.onIntent(OnBoardingInterviewIntent.Load)
        viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip)
        return viewModel
    }

    private fun TestScope.loadedForPreload(
        interviewRepository: InterviewRepository,
    ): OnBoardingInterviewViewModel {
        val viewModel =
            createViewModel(
                portfolioRepository =
                    FakePortfolioRepository(overviewResult = Result.success(readyOverview())),
                interviewRepository = interviewRepository,
            )
        viewModel.onIntent(OnBoardingInterviewIntent.Load)
        advanceUntilIdle()
        return viewModel
    }

    private fun moveToMainProject(viewModel: OnBoardingInterviewViewModel) {
        repeat(2) { viewModel.onIntent(OnBoardingInterviewIntent.ClickSkip) }
    }

    private fun readySessionRepo(sessionId: Long = 1L): FakeInterviewRepository =
        FakeInterviewRepository(
            createResult = Result.success(sessionResult(sessionId)),
            sessionStatusResults =
                listOf(
                    Result.success(sessionStatus(InterviewSessionStatusType.READY)),
                ),
        )

    private fun relevanceFailRepo(): FakeInterviewRepository =
        FakeInterviewRepository(
            createResult =
                Result.failure(
                    FreeTextNotRelevantException(
                        errCode = "FREETEXT_NOT_RELEVANT",
                        message = "무시되는 메시지",
                    ),
                ),
        )

    private companion object {
        const val MESSAGE_LINK_FORMAT = "올바른 URL 형식이 아니에요."
        const val MESSAGE_LINK_RATE_LIMIT = "공고 링크는 하루에 5번까지만 입력할 수 있어요"
        const val MESSAGE_TEXT_TOO_SHORT = "공고 내용은 200자 이상으로 입력해 주세요"
        const val MESSAGE_FREETEXT_TOO_SHORT = "집중 프로젝트 설명은 10자 이상 입력해 주세요"
        const val MESSAGE_FREETEXT_NOT_RELEVANT =
            "포트폴리오에서 그 내용을 찾지 못했어요. 포트폴리오에 있는 프로젝트로 다시 적어주세요"
        const val MESSAGE_PORTFOLIO_REQUIRED = "포트폴리오를 업로드해주세요"
        const val MESSAGE_PDF_SIZE = "파일이 너무 커요. 20MB 이하 PDF로 올려주세요"
        const val MESSAGE_PDF_PAGE = "페이지가 너무 많아요. 30페이지 이하 PDF로 올려주세요"
        const val MESSAGE_PDF_PASSWORD = "암호가 걸린 PDF는 열 수 없어요. 암호를 푼 PDF로 올려주세요"
        const val MESSAGE_PDF_CORRUPT = "파일이 손상된 것 같아요. 파일을 확인하고 다시 시도해 주세요"
        const val MESSAGE_PDF_UNREADABLE =
            "이 PDF에서 글자를 읽지 못했어요. 스캔본·이미지로 만든 PDF는 인식이 어려워요. " +
                "글자가 드래그로 선택되는 PDF로 다시 올려주세요"
        const val MESSAGE_PORTFOLIO_SYSTEM_FAILED =
            "포트폴리오 분석이 예상보다 오래 걸리고 있어요. 잠시 후 다시 시도해 주세요"
        const val PORTFOLIO_PROGRESS_COMPLETE = 100

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

        fun readyOverview(): PortfolioOverview =
            PortfolioOverview(
                portfolio = samplePortfolio(PortfolioStatus.READY),
                isReplaceAvailable = true,
                nextReplaceAvailableAt = null,
                isDeleteAvailable = true,
                nextDeleteAvailableAt = null,
            )

        fun existingOverview(
            replace: Boolean = true,
            delete: Boolean = true,
        ): PortfolioOverview =
            PortfolioOverview(
                portfolio = samplePortfolio(PortfolioStatus.PROCESSING),
                isReplaceAvailable = replace,
                nextReplaceAvailableAt = null,
                isDeleteAvailable = delete,
                nextDeleteAvailableAt = null,
            )

        fun emptyOverview(): PortfolioOverview =
            PortfolioOverview(
                portfolio = null,
                isReplaceAvailable = true,
                nextReplaceAvailableAt = null,
                isDeleteAvailable = true,
                nextDeleteAvailableAt = null,
            )

        fun samplePortfolio(status: PortfolioStatus): Portfolio =
            Portfolio(
                portfolioId = "pf-1",
                fileName = "이력서.pdf",
                fileSize = 1_000L,
                pageCount = 3,
                status = status,
                uploadedAt = null,
                isInterviewInProgress = false,
            )

        fun uploadAccepted(portfolioId: String): PortfolioUploadResult =
            PortfolioUploadResult(
                portfolioId = portfolioId,
                status = PortfolioStatus.PROCESSING,
                message = null,
            )

        fun portfolioStatus(status: PortfolioStatus): PortfolioUploadResult =
            PortfolioUploadResult(portfolioId = "pf-1", status = status, message = null)

        fun jdValidation(
            valid: Boolean,
            message: String? = null,
        ): JdValidationResult = JdValidationResult(valid = valid, reason = null, message = message)

        fun sessionResult(sessionId: Long = 1L): InterviewSessionResult =
            InterviewSessionResult(
                sessionId = sessionId,
                status = InterviewSessionStatusType.PROCESSING,
                statusUrl = "https://status",
            )

        fun sessionStatus(status: InterviewSessionStatusType): InterviewSessionStatus =
            InterviewSessionStatus(status = status, startedAt = null, summaryQuestion = null)
    }

    private class FakeUserRepository(
        private val profileResult: Result<UserProfile> = Result.success(sampleUserProfile),
    ) : UserRepository {
        override suspend fun getUserProfile(): UserProfile = profileResult.getOrThrow()

        override suspend fun updateUserProfile(update: UserProfileUpdate) = Unit

        override suspend fun withdraw() = Unit

        override suspend fun getJobList() = error("사용하지 않음")
    }

    private class FakePortfolioRepository(
        private val overviewResult: Result<PortfolioOverview> = Result.success(emptyOverview()),
        private val uploadResult: Result<PortfolioUploadResult> =
            Result.success(uploadAccepted("pf-1")),
        private val statusResults: List<Result<PortfolioUploadResult>> =
            listOf(Result.success(portfolioStatus(PortfolioStatus.READY))),
        private val repeatLastStatus: Boolean = false,
        private val deleteResult: Result<PortfolioDeleteResult> =
            Result.success(PortfolioDeleteResult("pf-1", "2026-08-11T10:00:00")),
    ) : PortfolioRepository {
        var uploadCallCount = 0
            private set
        var deleteCallCount = 0
            private set
        private var statusIndex = 0

        override suspend fun getPortfolioOverview(): PortfolioOverview = overviewResult.getOrThrow()

        override suspend fun uploadPortfolio(
            file: File,
            fileName: String,
            fileSize: Long?,
            pageCount: Int?,
            contentType: String,
        ): PortfolioUploadResult {
            uploadCallCount += 1
            return uploadResult.getOrThrow()
        }

        override suspend fun getPortfolioStatus(portfolioId: String): PortfolioUploadResult {
            val result =
                if (statusIndex <= statusResults.lastIndex) {
                    statusResults[statusIndex]
                } else if (repeatLastStatus) {
                    statusResults.last()
                } else {
                    statusResults.last()
                }
            statusIndex += 1
            return result.getOrThrow()
        }

        override suspend fun deletePortfolio(portfolioId: String): PortfolioDeleteResult {
            deleteCallCount += 1
            return deleteResult.getOrThrow()
        }
    }

    private class FakeInterviewRepository(
        private val validateResult: Result<JdValidationResult> =
            Result.success(jdValidation(valid = false)),
        private val validationDelayMs: Long = 0,
        private val createResult: Result<InterviewSessionResult> =
            Result.success(sessionResult()),
        private val sessionStatusResults: List<Result<InterviewSessionStatus>> =
            listOf(Result.success(sessionStatus(InterviewSessionStatusType.READY))),
        private val repeatLastSessionStatus: Boolean = false,
    ) : InterviewRepository {
        val validatedUrls = mutableListOf<String>()
        var createCallCount = 0
            private set
        var lastRequest: InterviewSessionRequest? = null
            private set
        private var sessionStatusIndex = 0

        override suspend fun validateJdUrl(jdUrl: String): JdValidationResult {
            validatedUrls += jdUrl
            if (validationDelayMs > 0) delay(validationDelayMs)
            return validateResult.getOrThrow()
        }

        override suspend fun createInterviewSession(
            request: InterviewSessionRequest,
        ): InterviewSessionResult {
            createCallCount += 1
            lastRequest = request
            return createResult.getOrThrow()
        }

        override suspend fun getInterviewSession(sessionId: Long): InterviewSessionStatus =
            error("사용하지 않음")

        override suspend fun getInterviewSessionStatus(sessionId: Long): InterviewSessionStatus {
            val result =
                if (sessionStatusIndex <= sessionStatusResults.lastIndex) {
                    sessionStatusResults[sessionStatusIndex]
                } else if (repeatLastSessionStatus) {
                    sessionStatusResults.last()
                } else {
                    sessionStatusResults.last()
                }
            sessionStatusIndex += 1
            return result.getOrThrow()
        }

        override suspend fun getReportList(): InterviewReportList = error("사용하지 않음")

        override suspend fun submitAnswer(
            command: SubmitInterviewAnswerCommand,
        ): SubmitAnswerResult = error("사용하지 않음")

        override fun getAudioStreamUrl(
            sessionId: Long,
            questionId: Long,
        ): String = error("사용하지 않음")

        override suspend fun uploadVideo(command: UploadInterviewVideoCommand) = error("사용하지 않음")

        override suspend fun getResume(sessionId: Long): InterviewResumeStatus = error("사용하지 않음")

        override suspend fun confirmResume(sessionId: Long): InterviewResumeConfirm =
            error("사용하지 않음")

        override suspend fun abandon(
            sessionId: Long,
            cause: InterviewAbandonRequestCause,
        ): InterviewAbandon = error("사용하지 않음")

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
}
