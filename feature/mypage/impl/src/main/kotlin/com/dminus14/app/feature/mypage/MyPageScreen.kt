@file:Suppress("ktlint:standard:filename", "TooManyFunctions")

package com.dminus14.app.feature.mypage

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dminus14.app.feature.mypage.component.MyPageModal
import com.dminus14.app.feature.mypage.component.MyPagePortfolioSection
import com.dminus14.app.feature.mypage.component.MyPageProfileSection
import com.dminus14.app.feature.mypage.component.MyPageReportSection
import com.dminus14.app.feature.mypage.component.MyPageTopBar
import com.dminus14.app.feature.mypage.component.MyPageWithdrawalModal
import com.dminus14.designsystem.component.loading.HilitLoadingIndicator
import com.dminus14.designsystem.theme.HilitTheme

@Composable
@Suppress("LongParameterList")
fun MyPageScreen(
    onClose: () -> Unit,
    onProfileEditRequested: () -> Unit,
    onReportViewRequested: (String) -> Unit,
    onGuestFeedbackRequested: (String) -> Unit,
    onLogoutCompleted: () -> Unit,
    onWithdrawalCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyPageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { pickedUri ->
                viewModel.onIntent(MyPageIntent.Portfolio.SelectPortfolioFile(pickedUri.toString()))
            }
        }

    LaunchedEffect(Unit) {
        viewModel.onIntent(MyPageIntent.Load)
        viewModel.effect.collect { effect ->
            when (effect) {
                MyPageEffect.CloseRequested -> {
                    onClose()
                }

                MyPageEffect.PortfolioSelectionRequested -> {
                    filePickerLauncher.launch(arrayOf("application/pdf"))
                }

                MyPageEffect.ProfileEditRequested -> {
                    onProfileEditRequested()
                }

                is MyPageEffect.ReportViewRequested -> {
                    onReportViewRequested(effect.reportId)
                }

                is MyPageEffect.GuestFeedbackRequested -> {
                    onGuestFeedbackRequested(effect.reportId)
                }

                is MyPageEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }

                MyPageEffect.LogoutCompleted -> {
                    onLogoutCompleted()
                }

                MyPageEffect.WithdrawalCompleted -> {
                    onWithdrawalCompleted()
                }
            }
        }
    }

    MyPageContent(
        state = state,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

@Composable
@Suppress("LongMethod")
fun MyPageContent(
    state: MyPageState,
    onIntent: (MyPageIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().background(HilitTheme.colors.hilitWhite),
    ) {
        MyPageTopBar(onCloseClick = { onIntent(MyPageIntent.ClickClose) })

        if (state.isInitialLoading) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(HilitTheme.colors.gray50),
                contentAlignment = Alignment.Center,
            ) {
                HilitLoadingIndicator()
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(HilitTheme.colors.gray50)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                MyPageProfileSection(
                    profile = state.profile,
                    remainingTicketCount = state.remainingTicketCount,
                    socialAccount = state.socialAccount,
                    isTicketInfoTooltipVisible = state.isTicketInfoTooltipVisible,
                    onProfileEditClick = { onIntent(MyPageIntent.Account.ClickProfileEdit) },
                    onTicketInfoClick = { onIntent(MyPageIntent.Account.ClickTicketInfo) },
                    onTicketInfoTooltipDismiss = {
                        onIntent(MyPageIntent.Account.DismissTicketInfoTooltip)
                    },
                    onLogoutClick = { onIntent(MyPageIntent.Account.ClickLogout) },
                )
                MyPagePortfolioSection(
                    portfolioState = state.portfolioState,
                    isUploadFailureTooltipVisible = state.isUploadFailureTooltipVisible,
                    onUploadClick = { onIntent(MyPageIntent.Portfolio.ClickUpload) },
                    onUploadCancelClick = { onIntent(MyPageIntent.Portfolio.ClickUploadCancel) },
                    onUploadRetryClick = { onIntent(MyPageIntent.Portfolio.ClickUploadRetry) },
                    onUploadFailureInfoClick = {
                        onIntent(
                            MyPageIntent.Portfolio.ClickUploadFailureInfo,
                        )
                    },
                    onUploadFailureTooltipDismiss = {
                        onIntent(MyPageIntent.Portfolio.DismissUploadFailureTooltip)
                    },
                    onPortfolioDeleteClick = {
                        onIntent(
                            MyPageIntent.Portfolio.ClickPortfolioDelete,
                        )
                    },
                    onPortfolioReuploadClick = {
                        onIntent(
                            MyPageIntent.Portfolio.ClickPortfolioReupload,
                        )
                    },
                )
                MyPageReportSection(
                    reports = state.reports,
                    expandedReportIds = state.expandedReportIds,
                    onReportToggleClick = { onIntent(MyPageIntent.Report.ToggleReport(it)) },
                    onReportViewClick = { onIntent(MyPageIntent.Report.ClickReportView(it)) },
                    onGuestFeedbackClick = { onIntent(MyPageIntent.Report.ClickGuestFeedback(it)) },
                )
                Spacer(modifier = Modifier.height(176.dp))
                Text(
                    text = "회원탈퇴",
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                onIntent(MyPageIntent.Account.ClickWithdrawal)
                            }.padding(vertical = 16.dp),
                    style = HilitTheme.typography.body6,
                    color = HilitTheme.colors.gray500,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    state.modalType?.let { modalType -> MyPageModals(modalType, state, onIntent) }
}

@Composable
private fun MyPageModals(
    modalType: MyPageModalType,
    state: MyPageState,
    onIntent: (MyPageIntent) -> Unit,
) {
    when (modalType) {
        MyPageModalType.WithdrawalNotice, MyPageModalType.WithdrawalConfirm -> {
            MyPageWithdrawalModal(
                type = modalType,
                isSubmitting = state.isSubmitting,
                onConfirm = { onIntent(MyPageIntent.ConfirmModal) },
                onClose = { onIntent(MyPageIntent.CloseModal) },
            )
        }

        else -> {
            val count =
                when (modalType) {
                    MyPageModalType.PortfolioReupload,
                    MyPageModalType.PortfolioReuploadUnavailable,
                    -> {
                        if (state.isPortfolioReplaceAvailable) 1 else 0
                    }

                    MyPageModalType.PortfolioDelete,
                    MyPageModalType.PortfolioDeleteUnavailable,
                    -> {
                        if (state.isPortfolioDeleteAvailable) 1 else 0
                    }

                    else -> {
                        null
                    }
                }
            MyPageModal(
                type = modalType,
                onConfirm = { onIntent(MyPageIntent.ConfirmModal) },
                onClose = { onIntent(MyPageIntent.CloseModal) },
                count = count,
                isSubmitting = state.isSubmitting,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 900)
@Composable
private fun MyPageContentPreview() {
    HilitTheme {
        MyPageContent(state = previewState(), onIntent = {})
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 900)
@Composable
private fun MyPageLoadingPreview() {
    HilitTheme {
        MyPageContent(state = MyPageState(isInitialLoading = true), onIntent = {})
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 900)
@Composable
private fun MyPageEmptyPreview() {
    HilitTheme {
        MyPageContent(
            state = previewState().copy(portfolioState = MyPagePortfolioState.Empty),
            onIntent = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 900)
@Composable
private fun MyPageUploadingPreview() {
    HilitTheme {
        MyPageContent(
            state =
                previewState().copy(
                    portfolioState = MyPagePortfolioState.Uploading("샘플 포트폴리오.pdf", progress = 50f),
                ),
            onIntent = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 900)
@Composable
private fun MyPageUploadCompletedPreview() {
    HilitTheme {
        MyPageContent(
            state =
                previewState().copy(
                    portfolioState = MyPagePortfolioState.Completed("샘플 포트폴리오.pdf"),
                ),
            onIntent = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375, heightDp = 900)
@Composable
private fun MyPageUploadFailedPreview() {
    HilitTheme {
        MyPageContent(
            state =
                previewState().copy(
                    portfolioState = MyPagePortfolioState.Failed("샘플 포트폴리오.pdf"),
                    isUploadFailureTooltipVisible = true,
                ),
            onIntent = {},
        )
    }
}

private fun previewState() =
    MyPageState(
        profile = MyPageProfileUiModel("샘플 사용자", "UX/UI 디자이너", "3년차"),
        remainingTicketCount = 2,
        socialAccount =
            MyPageSocialAccountUiModel(
                providerLabel = "카카오",
                displayAccount = "sam****@kakao.com",
                isKakaoProvider = true,
            ),
        portfolioState =
            MyPagePortfolioState.Uploaded(
                MyPagePortfolioUiModel("샘플 포트폴리오.pdf", "2026.08.03", "1.2MB"),
            ),
        reports =
            listOf(
                MyPageReportUiModel(
                    id = "synthetic-complete",
                    jobRole = MyPageJobRole.ANDROID,
                    jobRoleLabel = MyPageJobRole.ANDROID.label,
                    experienceYears = 3,
                    createdAt = "2026.08.03",
                    status = MyPageReportStatus.READY,
                    portfolioFileName = "샘플 포트폴리오.pdf",
                    jobDescription = "careers.example.com/jobs/1024",
                    isFeedbackAvailable = true,
                ),
                MyPageReportUiModel(
                    id = "synthetic-deleted",
                    jobRole = MyPageJobRole.IOS,
                    jobRoleLabel = MyPageJobRole.IOS.label,
                    experienceYears = 2,
                    createdAt = "2026.07.14",
                    status = MyPageReportStatus.READY,
                    portfolioFileName = "이전 샘플 포트폴리오.pdf",
                    jobDescription = "JD 직접 입력",
                    isFeedbackAvailable = true,
                ),
                MyPageReportUiModel(
                    id = "synthetic-failed",
                    jobRole = MyPageJobRole.BACKEND,
                    jobRoleLabel = MyPageJobRole.BACKEND.label,
                    experienceYears = 5,
                    createdAt = "2026.06.28",
                    status = MyPageReportStatus.FAILED,
                    portfolioFileName = "sample_backend_portfolio.pdf",
                    jobDescription = "careers.example.com/jobs/2048",
                    isFeedbackAvailable = false,
                ),
            ),
        expandedReportIds = setOf("synthetic-complete", "synthetic-failed"),
    )
