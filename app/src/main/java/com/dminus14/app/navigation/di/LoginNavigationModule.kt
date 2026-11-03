package com.dminus14.app.navigation.di

import androidx.navigation3.runtime.EntryProviderScope
import com.dminus14.app.feature.home.api.Home
import com.dminus14.app.feature.login.api.Login
import com.dminus14.app.feature.login.onboarding.onboardingEntryBuilder
import com.dminus14.app.feature.login.permission.permissionConsentDeniedEntryBuilder
import com.dminus14.app.feature.login.permission.permissionConsentEntryBuilder
import com.dminus14.app.feature.login.splash.splashEntryBuilder
import com.dminus14.app.feature.login.suspension.suspensionNoticeEntryBuilder
import com.dminus14.app.feature.login.term.termEntryBuilder
import com.dminus14.app.navigation.Navigator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(ActivityRetainedComponent::class)
object LoginNavigationModule {
    @IntoSet
    @Provides
    fun provideLoginEntryInstaller(navigator: Navigator): EntryProviderScope<Any>.() -> Unit =
        {
            splashEntryBuilder(onNavigate = navigator::replaceAll)
            termEntryBuilder(
                onNavigate = navigator::goTo,
                onClose = { navigator.replaceAll(Login) },
            )
            permissionConsentEntryBuilder(onNavigate = navigator::replaceAll)
            permissionConsentDeniedEntryBuilder(onHome = { navigator.replaceAll(Home) })
            suspensionNoticeEntryBuilder(onHome = { navigator.replaceAll(Home) })
            onboardingEntryBuilder(
                onNavigate = navigator::replaceAll,
                onClose = { navigator.replaceAll(Login) },
            )
        }
}
