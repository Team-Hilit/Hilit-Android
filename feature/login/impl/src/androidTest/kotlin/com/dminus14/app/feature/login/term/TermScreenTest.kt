package com.dminus14.app.feature.login.term

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso.pressBack
import com.dminus14.app.core.permission.AppPermission
import com.dminus14.app.core.permission.PermissionManager
import com.dminus14.app.domain.model.ConsentDocument
import com.dminus14.app.domain.model.ConsentItem
import com.dminus14.app.domain.model.ConsentItemCode
import com.dminus14.app.domain.model.ConsentPendingStatus
import com.dminus14.app.domain.model.ConsentSubmission
import com.dminus14.app.domain.model.Job
import com.dminus14.app.domain.model.PendingConsentList
import com.dminus14.app.domain.model.UserProfile
import com.dminus14.app.domain.model.UserProfileUpdate
import com.dminus14.app.domain.repository.ConsentRepository
import com.dminus14.app.domain.repository.UserRepository
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.GetConsentDocumentUseCase
import com.dminus14.app.domain.usecase.GetPendingConsentListUseCase
import com.dminus14.app.domain.usecase.SubmitConsentUseCase
import com.dminus14.designsystem.theme.HilitTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TermScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: TermViewModel
    private var closeCount = 0
    private var navigateCount = 0

    @Test
    fun `약관 화면에서 시스템 뒤로가기를 누르면 닫기를 요청한다`() {
        setScreen()

        pressBack()

        composeRule.runOnIdle {
            assertEquals(1, closeCount)
            assertEquals(0, navigateCount)
        }
    }

    @Test
    fun `약관 상세에서 뒤로가면 시트만 닫고 다시 뒤로가면 화면을 닫는다`() {
        setScreen()
        composeRule.runOnIdle {
            viewModel.onIntent(TermIntent.ClickViewTerm(0))
        }
        composeRule.onNodeWithText("합성 약관 본문").assertIsDisplayed()

        pressBack()

        composeRule.runOnIdle {
            assertNull(viewModel.state.value.visibleTermDetail)
            assertEquals(0, closeCount)
        }

        pressBack()

        composeRule.runOnIdle {
            assertEquals(1, closeCount)
        }
    }

    @Test
    fun `약관 조회 중 뒤로가기로 닫은 뒤 응답이 도착해도 추가 이동하지 않는다`() {
        val pending = CompletableDeferred<PendingConsentList>()
        setScreen(pending)

        pressBack()
        composeRule.runOnIdle {
            assertEquals(1, closeCount)
            pending.complete(PendingConsentList(ConsentPendingStatus.UP_TO_DATE, emptyList()))
        }

        composeRule.runOnIdle {
            assertEquals(0, navigateCount)
        }
    }

    private fun setScreen(
        pending: CompletableDeferred<PendingConsentList> = CompletableDeferred(samplePending),
    ) {
        composeRule.runOnUiThread {
            val repository = StubConsentRepository(pending)
            viewModel =
                TermViewModel(
                    GetPendingConsentListUseCase(repository),
                    GetConsentDocumentUseCase(repository),
                    SubmitConsentUseCase(repository),
                    CheckUserProfileUseCase(UnusedUserRepository),
                    DeniedPermissionManager,
                )
        }
        // 닫힌 뒤에도 잠시 남아 있는 entry를 재현하도록 callback에서 화면을 제거하지 않는다.
        composeRule.setContent {
            HilitTheme {
                TermScreen(
                    onNavigate = { navigateCount++ },
                    onClose = { closeCount++ },
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private class StubConsentRepository(
        private val pending: CompletableDeferred<PendingConsentList>,
    ) : ConsentRepository {
        override suspend fun getPendingConsentList(): PendingConsentList = pending.await()

        override suspend fun getConsentDocument(
            rawCode: String,
            version: Int,
        ): ConsentDocument =
            ConsentDocument(
                code = ConsentItemCode.TERMS_OF_SERVICE,
                rawCode = rawCode,
                title = "합성 약관",
                version = version,
                contentMarkdown = "합성 약관 본문",
            )

        override suspend fun submitConsent(submission: ConsentSubmission) = Unit
    }

    private object DeniedPermissionManager : PermissionManager {
        override fun isGranted(permission: AppPermission): Boolean = false

        override fun shouldShowRationale(
            permission: AppPermission,
            shouldShowRequestPermissionRationale: (manifestPermission: String) -> Boolean,
        ): Boolean = false
    }

    private object UnusedUserRepository : UserRepository {
        override suspend fun getUserProfile(): UserProfile = error("프로필 조회는 호출하지 않는다.")

        override suspend fun updateUserProfile(update: UserProfileUpdate) =
            error("프로필 수정은 호출하지 않는다.")

        override suspend fun withdraw() = error("탈퇴는 호출하지 않는다.")

        override suspend fun getJobList(): List<Job> = error("직군 조회는 호출하지 않는다.")
    }

    private companion object {
        val samplePending =
            PendingConsentList(
                status = ConsentPendingStatus.NOT_SUBMITTED,
                items =
                    listOf(
                        ConsentItem(
                            code = ConsentItemCode.TERMS_OF_SERVICE,
                            rawCode = "TERMS_OF_SERVICE",
                            label = "합성 약관 동의",
                            version = 1,
                            isRequired = true,
                            hasDocument = true,
                        ),
                    ),
            )
    }
}
