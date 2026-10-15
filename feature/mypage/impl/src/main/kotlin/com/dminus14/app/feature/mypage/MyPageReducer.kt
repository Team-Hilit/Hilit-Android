package com.dminus14.app.feature.mypage

import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.core.common.pdf.PdfInvalidReason
import com.dminus14.app.domain.model.InterviewReportList
import com.dminus14.app.domain.model.PortfolioOverview
import com.dminus14.app.domain.model.UserProfile

internal sealed interface MyPageMutation {
    sealed interface Loading : MyPageMutation

    sealed interface Upload : MyPageMutation

    sealed interface Processing : MyPageMutation

    sealed interface Modal : MyPageMutation

    sealed interface LocalUi : MyPageMutation

    data class ModalOpened(
        val type: MyPageModalType,
    ) : Modal

    data object LoadStarted : Loading

    data object LoadFinished : Loading

    data class ProfileLoaded(
        val profile: UserProfile,
    ) : Loading

    data class ReportsLoaded(
        val reportList: InterviewReportList,
    ) : Loading

    data class OverviewLoaded(
        val overview: PortfolioOverview,
    ) : Loading

    data class FileRejected(
        val fileName: String,
        val reason: PdfInvalidReason,
    ) : Upload

    data class UploadFailed(
        val fileName: String,
        val portfolioId: String? = null,
    ) : Upload

    data class UploadStarted(
        val fileName: String,
        val previousPortfolio: MyPagePortfolioUiModel?,
    ) : Upload

    data class UploadAccepted(
        val portfolioId: String,
        val fileName: String,
    ) : Upload

    data class PollingAdvanced(
        val fileName: String,
        val portfolioId: String,
        val elapsedSeconds: Int,
    ) : Processing

    data class UploadCompleted(
        val fileName: String,
    ) : Processing

    data object SubmissionStarted : Modal

    data object SubmissionFinished : Modal

    data class CancelFoundCompleted(
        val fileName: String,
    ) : Processing

    data class CancelFoundFailed(
        val fileName: String,
        val portfolioId: String,
    ) : Processing

    data class UploadReverted(
        val previousPortfolio: MyPagePortfolioUiModel?,
    ) : Processing

    data object ModalClosed : Modal

    data object ModalSubmissionFinished : Modal

    data object PortfolioDeleted : Processing

    data object UploadTooltipToggled : LocalUi

    data object UploadTooltipDismissed : LocalUi

    data object TicketTooltipToggled : LocalUi

    data object TicketTooltipDismissed : LocalUi

    data class ReportToggled(
        val id: String,
    ) : LocalUi
}

/** 모든 화면 상태 전이를 계산하며 작업이나 Effect를 실행하지 않는다. */
internal object MyPageReducer : MviReducer<MyPageState, MyPageMutation> {
    override fun reduce(
        state: MyPageState,
        mutation: MyPageMutation,
    ): MyPageState =
        when (mutation) {
            is MyPageMutation.Loading -> reduceLoading(state, mutation)
            is MyPageMutation.Upload -> reduceUpload(state, mutation)
            is MyPageMutation.Processing -> reduceProcessing(state, mutation)
            is MyPageMutation.Modal -> reduceModal(state, mutation)
            is MyPageMutation.LocalUi -> reduceLocalUi(state, mutation)
        }

    private fun reduceLoading(
        state: MyPageState,
        mutation: MyPageMutation.Loading,
    ): MyPageState =
        with(state) {
            when (mutation) {
                MyPageMutation.LoadStarted -> {
                    copy(isInitialLoading = true)
                }

                MyPageMutation.LoadFinished -> {
                    copy(isInitialLoading = false)
                }

                is MyPageMutation.ProfileLoaded -> {
                    copy(
                        profile = mutation.profile.toProfileUiModel(),
                        remainingTicketCount = mutation.profile.remainingTicketCount,
                        socialAccount = mutation.profile.toSocialAccountUiModel(),
                    )
                }

                is MyPageMutation.ReportsLoaded -> {
                    copy(reports = mutation.reportList.reports.map { it.toReportUiModel() })
                }

                is MyPageMutation.OverviewLoaded -> {
                    val portfolio = mutation.overview.portfolio
                    copy(
                        portfolioId = portfolio?.portfolioId,
                        portfolioState =
                            portfolio?.toPortfolioState(portfolioState)
                                ?: MyPagePortfolioState.Empty,
                        isPortfolioReplaceAvailable = mutation.overview.isReplaceAvailable,
                        isPortfolioDeleteAvailable = mutation.overview.isDeleteAvailable,
                        nextPortfolioAvailableAt = mutation.overview.nextReplaceAvailableAt,
                        nextPortfolioDeleteAvailableAt = mutation.overview.nextDeleteAvailableAt,
                        isInterviewInProgress = portfolio?.isInterviewInProgress ?: false,
                    )
                }
            }
        }

    private fun reduceUpload(
        state: MyPageState,
        mutation: MyPageMutation.Upload,
    ): MyPageState =
        with(state) {
            when (mutation) {
                is MyPageMutation.FileRejected -> {
                    copy(
                        portfolioState =
                            MyPagePortfolioState.Failed(
                                fileName = mutation.fileName,
                                invalidReason = mutation.reason,
                            ),
                    )
                }

                is MyPageMutation.UploadFailed -> {
                    copy(
                        portfolioState =
                            MyPagePortfolioState.Failed(
                                mutation.fileName,
                                mutation.portfolioId,
                            ),
                    )
                }

                is MyPageMutation.UploadStarted -> {
                    copy(
                        portfolioState =
                            MyPagePortfolioState.Uploading(
                                fileName = mutation.fileName,
                                previousPortfolio = mutation.previousPortfolio,
                            ),
                    )
                }

                is MyPageMutation.UploadAccepted -> {
                    copy(
                        portfolioId = mutation.portfolioId,
                        portfolioState =
                            MyPagePortfolioState.Uploading(
                                fileName = mutation.fileName,
                                portfolioId = mutation.portfolioId,
                            ),
                    )
                }
            }
        }

    private fun reduceProcessing(
        state: MyPageState,
        mutation: MyPageMutation.Processing,
    ): MyPageState =
        with(state) {
            when (mutation) {
                is MyPageMutation.PollingAdvanced -> {
                    copy(
                        portfolioState =
                            MyPagePortfolioState.Uploading(
                                fileName = mutation.fileName,
                                portfolioId = mutation.portfolioId,
                                progress =
                                    (
                                        mutation.elapsedSeconds / POLLING_TIMEOUT_SECONDS *
                                            MAX_POLLING_PROGRESS
                                    ).coerceAtMost(MAX_POLLING_PROGRESS),
                            ),
                    )
                }

                is MyPageMutation.UploadCompleted -> {
                    copy(
                        portfolioState = MyPagePortfolioState.Completed(mutation.fileName),
                    )
                }

                is MyPageMutation.CancelFoundCompleted -> {
                    copy(
                        isSubmitting = false,
                        modalType = MyPageModalType.UploadAlreadyCompleted,
                        portfolioState = MyPagePortfolioState.Completed(mutation.fileName),
                    )
                }

                is MyPageMutation.CancelFoundFailed -> {
                    copy(
                        isSubmitting = false,
                        portfolioState =
                            MyPagePortfolioState.Failed(
                                mutation.fileName,
                                mutation.portfolioId,
                            ),
                    )
                }

                is MyPageMutation.UploadReverted -> {
                    copy(
                        portfolioState =
                            mutation.previousPortfolio?.let(MyPagePortfolioState::Uploaded)
                                ?: MyPagePortfolioState.Empty,
                    )
                }

                MyPageMutation.PortfolioDeleted -> {
                    copy(portfolioId = null, portfolioState = MyPagePortfolioState.Empty)
                }
            }
        }

    private fun reduceModal(
        state: MyPageState,
        mutation: MyPageMutation.Modal,
    ): MyPageState =
        with(state) {
            when (mutation) {
                is MyPageMutation.ModalOpened -> {
                    copy(modalType = mutation.type)
                }

                MyPageMutation.SubmissionStarted -> {
                    copy(isSubmitting = true)
                }

                MyPageMutation.SubmissionFinished -> {
                    copy(isSubmitting = false)
                }

                MyPageMutation.ModalClosed -> {
                    copy(modalType = null)
                }

                MyPageMutation.ModalSubmissionFinished -> {
                    copy(isSubmitting = false, modalType = null)
                }
            }
        }

    private fun reduceLocalUi(
        state: MyPageState,
        mutation: MyPageMutation.LocalUi,
    ): MyPageState =
        with(state) {
            when (mutation) {
                MyPageMutation.UploadTooltipToggled -> {
                    if (portfolioState is MyPagePortfolioState.Failed) {
                        copy(
                            isUploadFailureTooltipVisible = !isUploadFailureTooltipVisible,
                        )
                    } else {
                        this
                    }
                }

                MyPageMutation.UploadTooltipDismissed -> {
                    copy(isUploadFailureTooltipVisible = false)
                }

                MyPageMutation.TicketTooltipToggled -> {
                    if (remainingTicketCount !=
                        null
                    ) {
                        copy(isTicketInfoTooltipVisible = !isTicketInfoTooltipVisible)
                    } else {
                        this
                    }
                }

                MyPageMutation.TicketTooltipDismissed -> {
                    copy(isTicketInfoTooltipVisible = false)
                }

                is MyPageMutation.ReportToggled -> {
                    if (reports.any { it.id == mutation.id }) {
                        copy(
                            expandedReportIds =
                                if (mutation.id in
                                    expandedReportIds
                                ) {
                                    expandedReportIds - mutation.id
                                } else {
                                    expandedReportIds +
                                        mutation.id
                                },
                        )
                    } else {
                        this
                    }
                }
            }
        }

    private const val POLLING_TIMEOUT_SECONDS = 120f
    private const val MAX_POLLING_PROGRESS = 95f
}
