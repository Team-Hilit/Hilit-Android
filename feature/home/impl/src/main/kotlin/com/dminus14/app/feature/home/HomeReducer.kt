package com.dminus14.app.feature.home

import com.dminus14.app.core.common.mvi.MviReducer
import com.dminus14.app.domain.time.InterviewTimeCalculator

internal sealed interface HomeMutation {
    data object LoadStarted : HomeMutation

    data object LoadStopped : HomeMutation

    data class ProfileLoaded(
        val name: String,
        val tickets: Int?,
    ) : HomeMutation

    data class ReportsLoaded(
        val reports: List<HomeReportItem>,
    ) : HomeMutation

    data class ReportToggled(
        val id: String,
    ) : HomeMutation

    data object RestartRequested : HomeMutation

    data object RestartCompleted : HomeMutation

    data object OverlayDismissed : HomeMutation

    data object TicketsExhausted : HomeMutation

    data object SessionAbsent : HomeMutation

    data class SessionResumable(
        val elapsedMillis: Long,
    ) : HomeMutation
}

/** 화면 데이터와 세션 오버레이의 모든 변경을 계산한다. */
internal object HomeReducer : MviReducer<HomeState, HomeMutation> {
    override fun reduce(
        state: HomeState,
        mutation: HomeMutation,
    ): HomeState =
        with(state) {
            when (mutation) {
                HomeMutation.LoadStarted -> {
                    copy(isLoading = true)
                }

                HomeMutation.LoadStopped -> {
                    copy(isLoading = false)
                }

                is HomeMutation.ProfileLoaded -> {
                    copy(
                        userName = mutation.name,
                        remainingTicketCount = mutation.tickets,
                    )
                }

                is HomeMutation.ReportsLoaded -> {
                    reportsLoaded(state, mutation.reports)
                }

                is HomeMutation.ReportToggled -> {
                    toggleReport(state, mutation.id)
                }

                HomeMutation.RestartRequested -> {
                    copy(
                        sessionStartOverlay = HomeSessionStartOverlayState.ConfirmRestart,
                    )
                }

                HomeMutation.RestartCompleted -> {
                    copy(isLoading = false, sessionStartOverlay = null)
                }

                HomeMutation.OverlayDismissed -> {
                    copy(sessionStartOverlay = null)
                }

                HomeMutation.TicketsExhausted -> {
                    copy(
                        sessionStartOverlay = HomeSessionStartOverlayState.NoTickets(userName),
                    )
                }

                HomeMutation.SessionAbsent -> {
                    copy(sessionStartOverlay = ticketOverlay(state))
                }

                is HomeMutation.SessionResumable -> {
                    copy(
                        sessionStartOverlay =
                            HomeSessionStartOverlayState.InProgress(
                                userName,
                                remainingQuestionCount(mutation.elapsedMillis),
                            ),
                    )
                }
            }
        }

    private fun reportsLoaded(
        state: HomeState,
        reports: List<HomeReportItem>,
    ): HomeState =
        state.copy(
            isLoading = false,
            reports = reports,
            expandedReportIds =
                if (state.reports.isEmpty()) {
                    reports.firstOrNull()?.let { setOf(it.id) }.orEmpty()
                } else {
                    state.expandedReportIds intersect reports.mapTo(mutableSetOf()) { it.id }
                },
        )

    private fun toggleReport(
        state: HomeState,
        id: String,
    ): HomeState =
        state.copy(
            expandedReportIds =
                if (id in state.expandedReportIds) {
                    state.expandedReportIds - id
                } else {
                    state.expandedReportIds + id
                },
        )

    private fun ticketOverlay(state: HomeState): HomeSessionStartOverlayState =
        if ((state.remainingTicketCount ?: 0) > 0) {
            HomeSessionStartOverlayState.Start(state.userName, state.remainingTicketCount ?: 0)
        } else {
            HomeSessionStartOverlayState.NoTickets(state.userName)
        }

    /** 서버에 남은 질문 수가 없어 기존 12분 타이머 규칙으로 환산한다. */
    private fun remainingQuestionCount(elapsedMillis: Long): Int {
        val seconds =
            (InterviewTimeCalculator.HARD_CAP_MILLIS - elapsedMillis).coerceAtLeast(0L) /
                MILLIS_PER_SECOND
        return when {
            seconds < ONE_QUESTION_THRESHOLD_SECONDS -> 1
            seconds < TWO_QUESTIONS_THRESHOLD_SECONDS -> 2
            seconds <= THREE_QUESTIONS_THRESHOLD_SECONDS -> THREE_QUESTIONS
            else -> FOUR_QUESTIONS
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val ONE_QUESTION_THRESHOLD_SECONDS = 180L
private const val TWO_QUESTIONS_THRESHOLD_SECONDS = 300L
private const val THREE_QUESTIONS_THRESHOLD_SECONDS = 420L

private const val THREE_QUESTIONS = 3
private const val FOUR_QUESTIONS = 4
