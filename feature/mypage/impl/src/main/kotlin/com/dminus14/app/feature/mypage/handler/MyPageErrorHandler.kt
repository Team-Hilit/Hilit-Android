package com.dminus14.app.feature.mypage.handler

import com.dminus14.app.core.common.event.GlobalAppEvent
import com.dminus14.app.core.common.event.GlobalErrorHandler
import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.exception.UnknownException
import com.dminus14.app.feature.mypage.MyPageEffect
import com.dminus14.app.feature.mypage.MyPageMutation
import com.dminus14.app.feature.mypage.MyPageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun MviContext<MyPageState, MyPageMutation, MyPageEffect>.handleMyPageError(
    error: Throwable,
) {
    if (routeMyPageGlobalError(error)) return
    sendEffect(
        MyPageEffect.ShowToast(
            error.message?.takeIf { it.isNotBlank() } ?: "알 수 없는 오류가 발생했습니다.",
        ),
    )
}

internal fun MviContext<MyPageState, MyPageMutation, MyPageEffect>.routeMyPageGlobalError(
    error: Throwable,
): Boolean {
    val event =
        when (error) {
            is NetworkUnavailableException -> GlobalAppEvent.ShowNetworkErrorAndExit
            is ServerException -> GlobalAppEvent.ShowServerErrorAndExit
            is UnknownException -> GlobalAppEvent.ShowUnknownError
            else -> null
        } ?: return false
    launch { GlobalErrorHandler.emit(event) }
    return true
}

internal suspend fun <T> Result<T>.rethrowMyPageCancellation(): Result<T> {
    currentCoroutineContext().ensureActive()
    val error = exceptionOrNull()
    if (error is CancellationException) throw error
    return this
}
