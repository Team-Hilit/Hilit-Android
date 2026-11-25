package com.dminus14.app.feature.interview.navigation

import androidx.navigation3.runtime.EntryProviderScope
import com.dminus14.app.feature.interview.api.ConfirmedInterviewResume
import com.dminus14.app.feature.interview.api.InterviewErrorRoute
import com.dminus14.app.feature.interview.api.InterviewRoute
import com.dminus14.app.feature.interview.error.InterviewErrorScreen
import com.dminus14.app.feature.interview.interview.InterviewCompletionReason
import com.dminus14.app.feature.interview.interview.InterviewScreen

fun EntryProviderScope<Any>.interviewEntryBuilder(
    onNavigateHome: () -> Unit,
    onNavigateError: (com.dminus14.app.feature.interview.api.InterviewErrorType) -> Unit,
    onResumeInterview: () -> Unit,
    onInterviewEnded: (InterviewCompletionReason, Long) -> Unit,
    onSttAcknowledged: () -> Unit,
) {
    entry<InterviewRoute> {
        InterviewScreen(
            onNavigateHome = onNavigateHome,
            onNavigateError = onNavigateError,
            onInterviewEnded = onInterviewEnded,
        )
    }

    entry<ConfirmedInterviewResume> { resume ->
        InterviewScreen(
            confirmedResume = resume,
            onNavigateHome = onNavigateHome,
            onNavigateError = onNavigateError,
            onInterviewEnded = onInterviewEnded,
        )
    }

    entry<InterviewErrorRoute> { key ->
        InterviewErrorScreen(
            errorType = key.errorType,
            onInterviewAbandoned = onNavigateHome,
            onSttAcknowledged = onSttAcknowledged,
            onResumeInterview = onResumeInterview,
        )
    }
}
