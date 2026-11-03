package com.dminus14.app.feature.login.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import com.dminus14.app.domain.model.Job
import com.dminus14.app.domain.model.UserProfile
import com.dminus14.app.domain.model.UserProfileUpdate
import com.dminus14.app.domain.repository.UserRepository
import com.dminus14.app.domain.usecase.GetJobListUseCase
import com.dminus14.app.domain.usecase.UpdateUserProfileUseCase
import com.dminus14.designsystem.theme.HilitTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: OnboardingViewModel
    private var closeCount = 0
    private var navigateCount = 0

    @Test
    fun `이름 입력에서 시스템 뒤로가기를 누르면 닫기를 요청한다`() {
        verifySystemBack(OnboardingStep.Naming)
    }

    @Test
    fun `직군 선택에서 시스템 뒤로가기를 누르면 이전 단계 대신 닫기를 요청한다`() {
        verifySystemBack(OnboardingStep.JobSelection)
    }

    @Test
    fun `연차 선택에서 시스템 뒤로가기를 누르면 이전 단계 대신 닫기를 요청한다`() {
        verifySystemBack(OnboardingStep.ExperienceSelection)
    }

    @Test
    fun `가입 확인에서 시스템 뒤로가기를 누르면 이전 단계 대신 닫기를 요청한다`() {
        verifySystemBack(OnboardingStep.RegisterDone)
    }

    @Test
    fun `화면의 이전으로 버튼은 닫기 대신 이전 입력 단계로 이동한다`() {
        setScreen(OnboardingStep.JobSelection)

        composeRule.onNodeWithText("이전으로").performClick()

        composeRule.runOnIdle {
            assertEquals(OnboardingStep.Naming, viewModel.state.value.step)
            assertEquals(0, closeCount)
        }
    }

    @Test
    fun `가입 확인의 시작하기 버튼은 제출 성공 후 홈 이동을 요청한다`() {
        setScreen(OnboardingStep.RegisterDone)

        composeRule.onNodeWithText("시작하기").performClick()

        composeRule.runOnIdle {
            assertEquals(1, navigateCount)
            assertEquals(0, closeCount)
        }
    }

    @Test
    fun `제출 중 뒤로가기로 닫은 뒤 성공 응답이 도착해도 추가 이동하지 않는다`() {
        val submission = CompletableDeferred<Unit>()
        setScreen(OnboardingStep.RegisterDone, submission)
        composeRule.runOnIdle {
            viewModel.onIntent(OnboardingIntent.ContinueClick)
            assertTrue(viewModel.state.value.isSubmitting)
        }

        pressBack()
        composeRule.runOnIdle {
            assertEquals(1, closeCount)
            submission.complete(Unit)
        }

        composeRule.runOnIdle {
            assertEquals(0, navigateCount)
        }
    }

    private fun verifySystemBack(step: OnboardingStep) {
        setScreen(step)

        pressBack()

        composeRule.runOnIdle {
            assertEquals(1, closeCount)
            assertEquals(0, navigateCount)
            assertEquals(step, viewModel.state.value.step)
        }
    }

    private fun setScreen(
        step: OnboardingStep,
        submission: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) {
        composeRule.runOnUiThread {
            val repository = StubUserRepository(submission)
            viewModel =
                OnboardingViewModel(
                    GetJobListUseCase(repository),
                    UpdateUserProfileUseCase(repository),
                )
        }
        // 닫힌 뒤에도 잠시 남아 있는 entry를 재현하도록 callback에서 화면을 제거하지 않는다.
        composeRule.setContent {
            HilitTheme {
                OnboardingScreen(
                    onNavigate = { navigateCount++ },
                    onClose = { closeCount++ },
                    viewModel = viewModel,
                )
            }
        }
        composeRule.runOnIdle {
            if (step >= OnboardingStep.JobSelection) {
                viewModel.onIntent(OnboardingIntent.NameChange("테스터"))
                viewModel.onIntent(OnboardingIntent.ContinueClick)
            }
            if (step >= OnboardingStep.ExperienceSelection) {
                viewModel.onIntent(OnboardingIntent.JobClick(0))
                viewModel.onIntent(OnboardingIntent.ContinueClick)
            }
            if (step == OnboardingStep.RegisterDone) {
                viewModel.onIntent(OnboardingIntent.ContinueClick)
            }
        }
        composeRule.waitForIdle()
    }

    private class StubUserRepository(
        private val submission: CompletableDeferred<Unit>,
    ) : UserRepository {
        override suspend fun getUserProfile(): UserProfile = error("프로필 조회는 호출하지 않는다.")

        override suspend fun updateUserProfile(update: UserProfileUpdate) {
            submission.await()
        }

        override suspend fun withdraw() = error("탈퇴는 호출하지 않는다.")

        override suspend fun getJobList(): List<Job> =
            listOf(Job(jobId = 1, jobRole = "BACKEND", label = "테스트 직군"))
    }
}
