package com.dminus14.app.feature.home.navigation

import androidx.navigation3.runtime.EntryProviderScope
import com.dminus14.app.feature.home.HomeScreen
import com.dminus14.app.feature.home.api.Home

fun EntryProviderScope<Any>.homeEntryBuilder(
    onNavigate: (Any) -> Unit,
    onReplaceAll: (Any) -> Unit,
    onResumeConfirmed: (Long, Long) -> Unit,
    onSessionInvalid: () -> Unit,
) {
    entry<Home> {
        HomeScreen(
            onNavigate = onNavigate,
            onReplaceAll = onReplaceAll,
            onResumeConfirmed = onResumeConfirmed,
            onSessionInvalid = onSessionInvalid,
        )
    }
}
