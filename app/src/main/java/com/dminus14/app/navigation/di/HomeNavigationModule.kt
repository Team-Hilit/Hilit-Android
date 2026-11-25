package com.dminus14.app.navigation.di

import androidx.navigation3.runtime.EntryProviderScope
import com.dminus14.app.feature.home.navigation.homeEntryBuilder
import com.dminus14.app.feature.interview.api.ConfirmedInterviewResume
import com.dminus14.app.feature.interview.api.InterviewErrorRoute
import com.dminus14.app.feature.interview.api.InterviewErrorType
import com.dminus14.app.navigation.Navigator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(ActivityRetainedComponent::class)
object HomeNavigationModule {
    @IntoSet
    @Provides
    fun provideHomeEntryInstaller(navigator: Navigator): EntryProviderScope<Any>.() -> Unit =
        {
            homeEntryBuilder(
                onNavigate = navigator::goTo,
                onReplaceAll = navigator::replaceAll,
                onResumeConfirmed = {
                    sessionId,
                    questionId,
                    ->
                    navigator.goTo(ConfirmedInterviewResume(sessionId, questionId))
                },
                onSessionInvalid = { navigator.goTo(InterviewErrorRoute(InterviewErrorType.STT)) },
            )
        }
}
