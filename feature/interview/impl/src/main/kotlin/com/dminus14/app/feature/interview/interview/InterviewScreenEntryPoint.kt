package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.permission.PermissionManager
import com.dminus14.app.feature.interview.interview.handler.InterviewScreenEffectHandler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface InterviewScreenEntryPoint {
    fun permissionManager(): PermissionManager

    fun effectHandler(): InterviewScreenEffectHandler

    fun networkMonitor(): InterviewNetworkMonitor
}
