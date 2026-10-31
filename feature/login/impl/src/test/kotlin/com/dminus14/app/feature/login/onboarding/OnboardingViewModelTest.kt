package com.dminus14.app.feature.login.onboarding

import com.dminus14.app.domain.exception.InvalidJobRoleException
import com.dminus14.app.domain.exception.NameAlreadyTakenException
import com.dminus14.app.domain.exception.ValidationException
import com.dminus14.app.domain.model.Job
import com.dminus14.app.domain.model.UserProfile
import com.dminus14.app.domain.model.UserProfileUpdate
import com.dminus14.app.domain.repository.UserRepository
import com.dminus14.app.domain.usecase.GetJobListUseCase
import com.dminus14.app.domain.usecase.UpdateUserProfileUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 이전 프로덕션의 `DefaultJobs` 하드코딩 목록이 서버 조회(GetJobListUseCase)로 대체되면서
// 이 값은 테스트의 "loadJobs가 채워 넣는 목록" 역할만 한다. 원래 어서션이 최소 3개 이상의
// 직군을 필요로 하기 때문에(예: `JobClick(2)`) 스텁이 반환하는 목록을 그대로 사용한다.
private val DefaultJobs =
    listOf(
        Job(jobId = 1, jobRole = "BACKEND", label = "백엔드"),
        Job(jobId = 2, jobRole = "FRONTEND", label = "프론트엔드"),
        Job(jobId = 3, jobRole = "ANDROID", label = "안드로이드"),
        Job(jobId = 4, jobRole = "IOS", label = "iOS"),
        Job(jobId = 5, jobRole = "DATA", label = "데이터"),
    )

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    // OnboardingViewModel.Load는 viewModelScope.launch로 loadJobs를 실행하므로
    // 단위 테스트에서도 Main dispatcher가 필요하다. Unconfined로 즉시 실행되게 한다.
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `초기 상태는 Naming 단계이고 계속하기 버튼이 비활성화된다`() {
        val viewModel = createViewModel()

        assertEquals(OnboardingStep.Naming, viewModel.state.value.step)
        assertEquals("", viewModel.state.value.name)
        assertFalse(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `초기 상태에서 직군과 연차 옵션 기본값이 채워져 있다`() {
        val viewModel = createViewModel()

        assertEquals(DefaultJobs, viewModel.state.value.jobs)
        assertEquals(DefaultExperienceOptions, viewModel.state.value.experienceOptions)
    }

    @Test
    fun `Load 시 jobs와 experienceOptions가 기본값으로 설정된다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.Load)

        assertEquals(DefaultJobs, viewModel.state.value.jobs)
        assertEquals(DefaultExperienceOptions, viewModel.state.value.experienceOptions)
    }

    @Test
    fun `Load 시 selectedExperienceIndex가 0으로 reset된다`() {
        val viewModel = createViewModel()
        advanceToExperienceSelection(viewModel)
        viewModel.onIntent(OnboardingIntent.ExperienceChange(5))
        assertEquals(5, viewModel.state.value.selectedExperienceIndex)

        viewModel.onIntent(OnboardingIntent.Load)

        assertEquals(0, viewModel.state.value.selectedExperienceIndex)
    }

    @Test
    fun `Load 시 step과 name과 selectedJobIndex는 유지된다`() {
        val viewModel = createViewModel()
        advanceToJobSelection(viewModel)
        viewModel.onIntent(OnboardingIntent.JobClick(2))

        viewModel.onIntent(OnboardingIntent.Load)

        assertEquals(OnboardingStep.JobSelection, viewModel.state.value.step)
        assertEquals("재원", viewModel.state.value.name)
        assertEquals(2, viewModel.state.value.selectedJobIndex)
    }

    @Test
    fun `Load 후 Naming 단계면 name 유효성에 따라 isContinueEnabled가 결정된다`() {
        val viewModel = createViewModel()
        viewModel.onIntent(OnboardingIntent.NameChange("재원"))

        viewModel.onIntent(OnboardingIntent.Load)

        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `빈 문자열 입력 시 name은 비어 있고 버튼이 비활성화된다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange(""))

        assertEquals("", viewModel.state.value.name)
        assertFalse(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `공백만 입력하면 필터되어 버튼이 비활성화된다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange("   "))

        assertEquals("", viewModel.state.value.name)
        assertFalse(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `한글 1글자 입력 시 name이 저장되고 버튼이 활성화된다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange("재"))

        assertEquals("재", viewModel.state.value.name)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `영문 1글자 입력 시 name이 저장되고 버튼이 활성화된다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange("a"))

        assertEquals("a", viewModel.state.value.name)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `한글과 영문 혼용 입력이 허용된다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange("재won"))

        assertEquals("재won", viewModel.state.value.name)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `5글자까지 입력이 허용된다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange("아아아아아"))

        assertEquals("아아아아아", viewModel.state.value.name)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `6글자 입력 시 5글자까지만 저장된다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange("아아아아아아"))

        assertEquals("아아아아아", viewModel.state.value.name)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `숫자는 필터되어 저장되지 않는다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange("재원1"))

        assertEquals("재원", viewModel.state.value.name)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `특수문자는 필터되어 저장되지 않는다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange("재@원!"))

        assertEquals("재원", viewModel.state.value.name)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `허용되지 않는 문자만 입력하면 name이 비어 버튼이 비활성화된다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange("123!@"))

        assertEquals("", viewModel.state.value.name)
        assertFalse(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `유효한 이름 입력 후 삭제하면 버튼이 다시 비활성화된다`() {
        val viewModel = createViewModel()
        viewModel.onIntent(OnboardingIntent.NameChange("재원"))
        assertTrue(viewModel.state.value.isContinueEnabled)

        viewModel.onIntent(OnboardingIntent.NameChange(""))

        assertEquals("", viewModel.state.value.name)
        assertFalse(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `자모 입력이 허용된다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.NameChange("ㄱㅏ"))

        assertEquals("ㄱㅏ", viewModel.state.value.name)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `이름이 유효하지 않을 때 ContinueClick은 step을 바꾸지 않는다`() {
        val viewModel = createViewModel()

        viewModel.onIntent(OnboardingIntent.ContinueClick)

        assertEquals(OnboardingStep.Naming, viewModel.state.value.step)
    }

    @Test
    fun `이름이 유효할 때 ContinueClick하면 JobSelection으로 이동한다`() {
        val viewModel = createViewModel()
        viewModel.onIntent(OnboardingIntent.NameChange("재원"))

        viewModel.onIntent(OnboardingIntent.ContinueClick)

        assertEquals(OnboardingStep.JobSelection, viewModel.state.value.step)
    }

    @Test
    fun `JobSelection 진입 후 이름이 유지된다`() {
        val viewModel = createViewModel()
        advanceToJobSelection(viewModel)

        assertEquals("재원", viewModel.state.value.name)
    }

    @Test
    fun `JobSelection 진입 직후 isContinueEnabled는 false다`() {
        val viewModel = createViewModel()
        advanceToJobSelection(viewModel)

        assertFalse(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `Naming에서 PreviousClick하면 CloseRequested Effect를 발행한다`() =
        runTest {
            val viewModel = createViewModel()
            val effect = async { viewModel.effect.first() }

            viewModel.onIntent(OnboardingIntent.PreviousClick)

            assertEquals(OnboardingEffect.CloseRequested, effect.await())
        }

    @Test
    fun `Naming에서 CloseClick하면 CloseRequested Effect를 발행한다`() =
        runTest {
            val viewModel = createViewModel()
            val effect = async { viewModel.effect.first() }

            viewModel.onIntent(OnboardingIntent.CloseClick)

            assertEquals(OnboardingEffect.CloseRequested, effect.await())
        }

    @Test
    fun `직군 미선택 시 isContinueEnabled는 false다`() {
        val viewModel = createViewModel()
        advanceToJobSelection(viewModel)

        assertFalse(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `직군 선택 시 isContinueEnabled가 true가 된다`() {
        val viewModel = createViewModel()
        advanceToJobSelection(viewModel)

        viewModel.onIntent(OnboardingIntent.JobClick(2))

        assertEquals(2, viewModel.state.value.selectedJobIndex)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `직군 선택 후 다른 직군으로 변경할 수 있다`() {
        val viewModel = createViewModel()
        advanceToJobSelection(viewModel)
        viewModel.onIntent(OnboardingIntent.JobClick(2))

        viewModel.onIntent(OnboardingIntent.JobClick(4))

        assertEquals(4, viewModel.state.value.selectedJobIndex)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `직군 미선택 상태에서 ContinueClick은 step을 바꾸지 않는다`() {
        val viewModel = createViewModel()
        advanceToJobSelection(viewModel)

        viewModel.onIntent(OnboardingIntent.ContinueClick)

        assertEquals(OnboardingStep.JobSelection, viewModel.state.value.step)
    }

    @Test
    fun `직군 선택 후 ContinueClick하면 ExperienceSelection으로 이동한다`() {
        val viewModel = createViewModel()
        advanceToJobSelection(viewModel)
        viewModel.onIntent(OnboardingIntent.JobClick(2))

        viewModel.onIntent(OnboardingIntent.ContinueClick)

        assertEquals(OnboardingStep.ExperienceSelection, viewModel.state.value.step)
    }

    @Test
    fun `ExperienceSelection 진입 직후 isContinueEnabled는 true다`() {
        val viewModel = createViewModel()
        advanceToExperienceSelection(viewModel)

        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `JobSelection에서 PreviousClick하면 Naming으로 돌아간다`() {
        val viewModel = createViewModel()
        advanceToJobSelection(viewModel)

        viewModel.onIntent(OnboardingIntent.PreviousClick)

        assertEquals(OnboardingStep.Naming, viewModel.state.value.step)
        assertEquals("재원", viewModel.state.value.name)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `기본 selectedExperienceIndex 0이면 isContinueEnabled는 true다`() {
        val viewModel = createViewModel()
        advanceToExperienceSelection(viewModel)

        assertEquals(0, viewModel.state.value.selectedExperienceIndex)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `ExperienceChange로 연차 선택이 변경된다`() {
        val viewModel = createViewModel()
        advanceToExperienceSelection(viewModel)

        viewModel.onIntent(OnboardingIntent.ExperienceChange(5))

        assertEquals(5, viewModel.state.value.selectedExperienceIndex)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `유효한 index면 ContinueClick 후 RegisterDone으로 이동한다`() {
        val viewModel = createViewModel()
        advanceToExperienceSelection(viewModel)

        viewModel.onIntent(OnboardingIntent.ContinueClick)

        assertEquals(OnboardingStep.RegisterDone, viewModel.state.value.step)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `ExperienceSelection에서 PreviousClick하면 JobSelection으로 돌아간다`() {
        val viewModel = createViewModel()
        advanceToExperienceSelection(viewModel)

        viewModel.onIntent(OnboardingIntent.PreviousClick)

        assertEquals(OnboardingStep.JobSelection, viewModel.state.value.step)
        assertEquals(2, viewModel.state.value.selectedJobIndex)
        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `RegisterDone에서 isContinueEnabled는 항상 true다`() {
        val viewModel = createViewModel()
        advanceToRegisterDone(viewModel)

        assertTrue(viewModel.state.value.isContinueEnabled)
    }

    @Test
    fun `RegisterDone에서 ContinueClick하면 Completed Effect를 발행한다`() =
        runTest {
            val viewModel = createViewModel()
            advanceToRegisterDone(viewModel)
            val effect = async { viewModel.effect.first() }

            viewModel.onIntent(OnboardingIntent.ContinueClick)

            assertEquals(OnboardingEffect.Completed, effect.await())
        }

    @Test
    fun `마지막 연차 옵션을 골라도 서버 허용 범위(0~10) 안의 careerYears가 전송된다`() =
        runTest {
            val fakeUserRepository = StubUserRepository()
            val viewModel =
                OnboardingViewModel(
                    getJobList = GetJobListUseCase(fakeUserRepository),
                    updateUserProfile = UpdateUserProfileUseCase(fakeUserRepository),
                )
            viewModel.onIntent(OnboardingIntent.Load)
            advanceToExperienceSelection(viewModel)

            viewModel.onIntent(
                OnboardingIntent.ExperienceChange(DefaultExperienceOptions.lastIndex),
            )
            viewModel.onIntent(OnboardingIntent.ContinueClick) // -> RegisterDone
            viewModel.onIntent(OnboardingIntent.ContinueClick) // submitProfile
            advanceUntilIdle()

            assertEquals(11, DefaultExperienceOptions.size)
            assertEquals("10년 이상", DefaultExperienceOptions.last())
            assertEquals(10, fakeUserRepository.lastUpdate?.careerYears)
        }

    @Test
    fun `RegisterDone에서 PreviousClick하면 ExperienceSelection으로 돌아간다`() {
        val viewModel = createViewModel()
        advanceToRegisterDone(viewModel)

        viewModel.onIntent(OnboardingIntent.PreviousClick)

        assertEquals(OnboardingStep.ExperienceSelection, viewModel.state.value.step)
    }

    @Test
    fun `이름 입력부터 RegisterDone까지 step과 입력값이 순서대로 유지된다`() {
        val viewModel = createViewModel()
        viewModel.onIntent(OnboardingIntent.NameChange("재won"))
        viewModel.onIntent(OnboardingIntent.ContinueClick)
        viewModel.onIntent(OnboardingIntent.JobClick(3))
        viewModel.onIntent(OnboardingIntent.ContinueClick)
        viewModel.onIntent(OnboardingIntent.ExperienceChange(4))
        viewModel.onIntent(OnboardingIntent.ContinueClick)

        with(viewModel.state.value) {
            assertEquals(OnboardingStep.RegisterDone, step)
            assertEquals("재won", name)
            assertEquals(3, selectedJobIndex)
            assertEquals(4, selectedExperienceIndex)
        }
    }

    @Test
    fun `RegisterDone까지 간 뒤 Previous로 Experience까지 되돌리고 다시 진행할 수 있다`() {
        val viewModel = createViewModel()
        advanceToRegisterDone(viewModel)

        viewModel.onIntent(OnboardingIntent.PreviousClick)
        assertEquals(OnboardingStep.ExperienceSelection, viewModel.state.value.step)

        viewModel.onIntent(OnboardingIntent.ContinueClick)
        assertEquals(OnboardingStep.RegisterDone, viewModel.state.value.step)
    }

    @Test
    fun `JobSelection에서 CloseClick하면 CloseRequested Effect를 발행한다`() =
        runTest {
            val viewModel = createViewModel()
            advanceToJobSelection(viewModel)
            val effect = async { viewModel.effect.first() }

            viewModel.onIntent(OnboardingIntent.CloseClick)

            assertEquals(OnboardingEffect.CloseRequested, effect.await())
            assertEquals(OnboardingStep.JobSelection, viewModel.state.value.step)
        }

    @Test
    fun `연차 선택에서 닫기를 누르면 이전 단계로 이동하지 않고 닫기를 요청한다`() =
        runTest {
            val viewModel = createViewModel()
            advanceToExperienceSelection(viewModel)
            val effect = async { viewModel.effect.first() }

            viewModel.onIntent(OnboardingIntent.CloseClick)

            assertEquals(OnboardingEffect.CloseRequested, effect.await())
            assertEquals(OnboardingStep.ExperienceSelection, viewModel.state.value.step)
        }

    @Test
    fun `가입 확인에서 닫기를 누르면 가입을 제출하지 않고 닫기를 요청한다`() =
        runTest {
            val repository = StubUserRepository()
            val viewModel =
                OnboardingViewModel(
                    GetJobListUseCase(repository),
                    UpdateUserProfileUseCase(repository),
                )
            viewModel.onIntent(OnboardingIntent.Load)
            advanceToRegisterDone(viewModel)
            val effect = async { viewModel.effect.first() }

            viewModel.onIntent(OnboardingIntent.CloseClick)

            assertEquals(OnboardingEffect.CloseRequested, effect.await())
            assertEquals(0, repository.updateCallCount)
            assertEquals(OnboardingStep.RegisterDone, viewModel.state.value.step)
        }

    @Test
    fun `ContinueClick이 disabled일 때 Effect가 발행되지 않는다`() =
        runTest {
            val viewModel = createViewModel()
            val receivedEffects = mutableListOf<OnboardingEffect>()
            val collector = launch { viewModel.effect.collect(receivedEffects::add) }

            viewModel.onIntent(OnboardingIntent.ContinueClick)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(emptyList<OnboardingEffect>(), receivedEffects)
        }

    // region 직군 목록 로드

    @Test
    fun `Load 시 GetJobListUseCase 결과가 jobs에 반영된다`() {
        val viewModel = createViewModel()

        assertEquals(DefaultJobs, viewModel.state.value.jobs)
    }

    // endregion

    // region 프로필 등록 제출

    @Test
    fun `RegisterDone에서 ContinueClick하면 UpdateUserProfileUseCase가 호출되고 Completed Effect가 발행된다`() =
        runTest {
            val viewModel = createViewModel()
            advanceToRegisterDone(viewModel)
            val effect = async { viewModel.effect.first() }

            viewModel.onIntent(OnboardingIntent.ContinueClick)

            assertEquals(OnboardingEffect.Completed, effect.await())
            assertFalse(viewModel.state.value.isSubmitting)
        }

    @Test
    fun `NameAlreadyTakenException 이면 Naming 단계로 되돌리고 errorMessage를 설정한다`() =
        runTest {
            val viewModel =
                createViewModel(
                    updateProfileFailure =
                        NameAlreadyTakenException(
                            errCode = "NAME_ALREADY_TAKEN",
                            message = "이미 사용 중인 이름이에요.",
                        ),
                )
            advanceToRegisterDone(viewModel)
            val receivedEffects = mutableListOf<OnboardingEffect>()
            val collector = launch { viewModel.effect.collect(receivedEffects::add) }

            viewModel.onIntent(OnboardingIntent.ContinueClick)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(OnboardingStep.Naming, viewModel.state.value.step)
            assertEquals("이미 사용 중인 이름이에요.", viewModel.state.value.errorMessage)
            assertFalse(viewModel.state.value.isSubmitting)
            assertEquals(emptyList<OnboardingEffect>(), receivedEffects)
        }

    @Test
    fun `InvalidJobRoleException 이면 JobSelection 단계로 되돌리고 errorMessage를 설정한다`() =
        runTest {
            val viewModel =
                createViewModel(
                    updateProfileFailure =
                        InvalidJobRoleException(
                            errCode = "INVALID_JOB_ROLE",
                            message = "잘못된 직무 코드예요.",
                        ),
                )
            advanceToRegisterDone(viewModel)

            viewModel.onIntent(OnboardingIntent.ContinueClick)
            advanceUntilIdle()

            assertEquals(OnboardingStep.JobSelection, viewModel.state.value.step)
            assertEquals("잘못된 직무 코드예요.", viewModel.state.value.errorMessage)
            assertFalse(viewModel.state.value.isSubmitting)
        }

    @Test
    fun `프로필 검증 오류는 현재 단계를 유지하고 사용자에게 오류를 안내한다`() =
        runTest {
            val viewModel =
                createViewModel(
                    updateProfileFailure =
                        ValidationException(
                            errCode = "VALIDATION_ERROR",
                            message = "입력값이 올바르지 않아요.",
                        ),
                )
            advanceToRegisterDone(viewModel)
            val effect = async { viewModel.effect.first() }

            viewModel.onIntent(OnboardingIntent.ContinueClick)
            advanceUntilIdle()

            assertEquals(OnboardingStep.RegisterDone, viewModel.state.value.step)
            assertEquals("입력값이 올바르지 않아요.", viewModel.state.value.errorMessage)
            assertEquals(OnboardingEffect.ShowToast("입력값이 올바르지 않아요."), effect.await())
            assertFalse(viewModel.state.value.isSubmitting)
        }

    // endregion

    private fun createViewModel(updateProfileFailure: Throwable? = null): OnboardingViewModel {
        val fakeUserRepository = StubUserRepository(updateFailure = updateProfileFailure)
        val viewModel =
            OnboardingViewModel(
                getJobList = GetJobListUseCase(fakeUserRepository),
                updateUserProfile = UpdateUserProfileUseCase(fakeUserRepository),
            )
        // 기존 테스트들은 jobs 목록이 즉시 채워져 있다는 전제(예: JobClick(2))를 가지므로
        // ViewModel 생성 직후 Load를 흘려 loadJobs가 DefaultJobs를 반영하게 한다.
        // UnconfinedTestDispatcher 덕분에 여기서 동기적으로 완료된다.
        viewModel.onIntent(OnboardingIntent.Load)
        return viewModel
    }

    /**
     * OnboardingViewModel 생성자 요구를 만족시키는 스텁.
     * 직군 목록은 [DefaultJobs]를 반환하고, updateUserProfile은 [updateFailure]가 주어지면 그것을 던진다.
     */
    private class StubUserRepository(
        private val updateFailure: Throwable? = null,
    ) : UserRepository {
        var updateCallCount: Int = 0
            private set
        var lastUpdate: UserProfileUpdate? = null
            private set

        override suspend fun getUserProfile(): UserProfile =
            error("Not used in OnboardingViewModelTest")

        override suspend fun updateUserProfile(update: UserProfileUpdate) {
            updateCallCount += 1
            lastUpdate = update
            updateFailure?.let { throw it }
        }

        override suspend fun withdraw() = error("Not used in OnboardingViewModelTest")

        override suspend fun getJobList(): List<Job> = DefaultJobs
    }

    private fun advanceToJobSelection(viewModel: OnboardingViewModel) {
        viewModel.onIntent(OnboardingIntent.NameChange("재원"))
        viewModel.onIntent(OnboardingIntent.ContinueClick)
    }

    private fun advanceToExperienceSelection(viewModel: OnboardingViewModel) {
        advanceToJobSelection(viewModel)
        viewModel.onIntent(OnboardingIntent.JobClick(2))
        viewModel.onIntent(OnboardingIntent.ContinueClick)
    }

    private fun advanceToRegisterDone(viewModel: OnboardingViewModel) {
        advanceToExperienceSelection(viewModel)
        viewModel.onIntent(OnboardingIntent.ContinueClick)
    }
}
