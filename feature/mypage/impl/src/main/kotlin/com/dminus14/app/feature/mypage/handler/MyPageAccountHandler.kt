package com.dminus14.app.feature.mypage.handler

import com.dminus14.app.core.common.mvi.MviContext
import com.dminus14.app.domain.usecase.LogoutUseCase
import com.dminus14.app.domain.usecase.WithdrawUserUseCase
import com.dminus14.app.feature.mypage.MyPageEffect
import com.dminus14.app.feature.mypage.MyPageModalType
import com.dminus14.app.feature.mypage.MyPageMutation
import com.dminus14.app.feature.mypage.MyPageState

/** 로그아웃과 탈퇴 요청을 처리한다. */
internal class MyPageAccountHandler(
    private val context: MviContext<MyPageState, MyPageMutation, MyPageEffect>,
    private val logoutUseCase: LogoutUseCase,
    private val withdrawUserUseCase: WithdrawUserUseCase,
) {
    fun logout() {
        if (context.state.value.isSubmitting) return
        context.dispatch(MyPageMutation.SubmissionStarted)
        context.launch {
            val error = logoutUseCase().rethrowMyPageCancellation().exceptionOrNull()
            context.dispatch(MyPageMutation.ModalSubmissionFinished)
            if (error == null) {
                context.sendEffect(MyPageEffect.LogoutCompleted)
            } else {
                context.handleMyPageError(error)
            }
        }
    }

    // 기존 계약대로 포트폴리오 개요의 진행 중 면접 플래그를 사용한다.
    // 서버의 전용 탈퇴 차단 오류 코드는 아직 없어 이 판정의 범위를 확장하지 않는다.
    fun requestWithdrawal() {
        if (context.state.value.isInterviewInProgress) {
            context.dispatch(MyPageMutation.ModalOpened(MyPageModalType.WithdrawalBlocked))
            return
        }
        context.dispatch(MyPageMutation.ModalOpened(MyPageModalType.WithdrawalNotice))
    }

    fun withdraw() {
        if (context.state.value.isSubmitting) return
        context.dispatch(MyPageMutation.SubmissionStarted)
        context.launch {
            val error = withdrawUserUseCase().rethrowMyPageCancellation().exceptionOrNull()
            context.dispatch(MyPageMutation.ModalSubmissionFinished)
            if (error == null) {
                context.sendEffect(MyPageEffect.WithdrawalCompleted)
            } else {
                context.handleMyPageError(error)
            }
        }
    }
}
