package com.dminus14.app.feature.mypage

import com.dminus14.app.core.common.mvi.MviViewModel
import com.dminus14.app.domain.usecase.CheckUserProfileUseCase
import com.dminus14.app.domain.usecase.DeletePortfolioUseCase
import com.dminus14.app.domain.usecase.GetInterviewReportListUseCase
import com.dminus14.app.domain.usecase.GetPortfolioOverviewUseCase
import com.dminus14.app.domain.usecase.GetPortfolioStatusUseCase
import com.dminus14.app.domain.usecase.LogoutUseCase
import com.dminus14.app.domain.usecase.UploadPortfolioUseCase
import com.dminus14.app.domain.usecase.WithdrawUserUseCase
import com.dminus14.app.feature.mypage.handler.MyPageAccountHandler
import com.dminus14.app.feature.mypage.handler.MyPageLoadEvent
import com.dminus14.app.feature.mypage.handler.MyPageLoadHandler
import com.dminus14.app.feature.mypage.handler.MyPagePortfolioHandler
import com.dminus14.app.feature.mypage.portfolio.PortfolioFileReader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MyPageViewModel
    @Inject
    @Suppress("LongParameterList")
    constructor(
        checkUserProfileUseCase: CheckUserProfileUseCase,
        getPortfolioOverviewUseCase: GetPortfolioOverviewUseCase,
        uploadPortfolioUseCase: UploadPortfolioUseCase,
        getPortfolioStatusUseCase: GetPortfolioStatusUseCase,
        deletePortfolioUseCase: DeletePortfolioUseCase,
        getInterviewReportListUseCase: GetInterviewReportListUseCase,
        logoutUseCase: LogoutUseCase,
        withdrawUserUseCase: WithdrawUserUseCase,
        portfolioFileReader: PortfolioFileReader,
    ) : MviViewModel<MyPageIntent, MyPageState, MyPageEffect>(MyPageState()) {
        private val context = createContext(MyPageReducer)
        private val portfolio =
            MyPagePortfolioHandler(
                context,
                getPortfolioOverviewUseCase,
                uploadPortfolioUseCase,
                getPortfolioStatusUseCase,
                deletePortfolioUseCase,
                portfolioFileReader,
            )
        private val account = MyPageAccountHandler(context, logoutUseCase, withdrawUserUseCase)
        private val initialLoad =
            MyPageLoadHandler(
                context,
                checkUserProfileUseCase,
                getPortfolioOverviewUseCase,
                getInterviewReportListUseCase,
                portfolioFileReader,
            ) { event ->
                when (event) {
                    is MyPageLoadEvent.ProcessingPortfolio -> {
                        portfolio.startPolling(
                            event.id,
                            event.fileName,
                        )
                    }
                }
            }

        override fun onIntent(intent: MyPageIntent) {
            when (intent) {
                MyPageIntent.Load -> initialLoad.load()
                MyPageIntent.ClickClose -> context.sendEffect(MyPageEffect.CloseRequested)
                is MyPageIntent.Portfolio -> onPortfolioIntent(intent)
                is MyPageIntent.Account -> onAccountIntent(intent)
                is MyPageIntent.Report -> onReportIntent(intent)
                MyPageIntent.ConfirmModal -> confirmModal()
                MyPageIntent.CloseModal -> closeModal()
            }
        }

        private fun onPortfolioIntent(intent: MyPageIntent.Portfolio) {
            when (intent) {
                MyPageIntent.Portfolio.ClickUpload, MyPageIntent.Portfolio.ClickUploadRetry -> {
                    context.sendEffect(MyPageEffect.PortfolioSelectionRequested)
                }

                is MyPageIntent.Portfolio.SelectPortfolioFile -> {
                    portfolio.selectPortfolioFile(intent.uri)
                }

                MyPageIntent.Portfolio.ClickUploadCancel -> {
                    portfolio.cancelUpload()
                }

                MyPageIntent.Portfolio.ClickPortfolioDelete -> {
                    portfolio.requestPortfolioDelete()
                }

                MyPageIntent.Portfolio.ClickPortfolioReupload -> {
                    portfolio.requestPortfolioReupload()
                }

                MyPageIntent.Portfolio.ClickUploadFailureInfo -> {
                    context.dispatch(MyPageMutation.UploadTooltipToggled)
                }

                MyPageIntent.Portfolio.DismissUploadFailureTooltip -> {
                    context.dispatch(MyPageMutation.UploadTooltipDismissed)
                }
            }
        }

        private fun onAccountIntent(intent: MyPageIntent.Account) {
            when (intent) {
                MyPageIntent.Account.ClickProfileEdit -> {
                    context.sendEffect(
                        MyPageEffect.ProfileEditRequested,
                    )
                }

                MyPageIntent.Account.ClickTicketInfo -> {
                    context.dispatch(
                        MyPageMutation.TicketTooltipToggled,
                    )
                }

                MyPageIntent.Account.DismissTicketInfoTooltip -> {
                    context.dispatch(
                        MyPageMutation.TicketTooltipDismissed,
                    )
                }

                MyPageIntent.Account.ClickLogout -> {
                    context.dispatch(
                        MyPageMutation.ModalOpened(MyPageModalType.Logout),
                    )
                }

                MyPageIntent.Account.ClickWithdrawal -> {
                    account.requestWithdrawal()
                }
            }
        }

        private fun onReportIntent(intent: MyPageIntent.Report) {
            when (intent) {
                is MyPageIntent.Report.ToggleReport -> {
                    context.dispatch(
                        MyPageMutation.ReportToggled(intent.id),
                    )
                }

                is MyPageIntent.Report.ClickReportView -> {
                    context.sendEffect(
                        MyPageEffect.ReportViewRequested(intent.reportId),
                    )
                }

                is MyPageIntent.Report.ClickGuestFeedback -> {
                    context.sendEffect(
                        MyPageEffect.GuestFeedbackRequested(intent.reportId),
                    )
                }
            }
        }

        private fun confirmModal() {
            when (state.value.modalType) {
                MyPageModalType.PortfolioReupload -> {
                    context.dispatch(MyPageMutation.ModalClosed)
                    context.sendEffect(MyPageEffect.PortfolioSelectionRequested)
                }

                MyPageModalType.PortfolioDelete -> {
                    portfolio.deletePortfolio()
                }

                MyPageModalType.Logout -> {
                    account.logout()
                }

                MyPageModalType.WithdrawalNotice -> {
                    context.dispatch(MyPageMutation.ModalOpened(MyPageModalType.WithdrawalConfirm))
                }

                MyPageModalType.WithdrawalConfirm -> {
                    account.withdraw()
                }

                else -> {
                    Unit
                }
            }
        }

        private fun closeModal() {
            val wasUploadAlreadyCompleted =
                state.value.modalType == MyPageModalType.UploadAlreadyCompleted
            context.dispatch(MyPageMutation.ModalClosed)
            if (wasUploadAlreadyCompleted) {
                context.launch { portfolio.refreshOverviewSilently() }
            }
        }
    }
