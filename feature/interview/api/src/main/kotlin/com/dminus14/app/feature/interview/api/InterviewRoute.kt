package com.dminus14.app.feature.interview.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 면접 진행 화면 route */
@Serializable
data object InterviewRoute : NavKey

/** 홈에서 A6로 확정한 면접과 최신 질문의 식별자다. */
@Serializable
data class ConfirmedInterviewResume(
    val sessionId: Long,
    val questionId: Long,
) : NavKey

/** 면접 오류 유형 */
enum class InterviewErrorType {
    MIC_DEVICE,
    NETWORK,
    STT,
    SERVER_TEMPORARY,
}

/** 면접 오류 화면 route */
@Serializable
data class InterviewErrorRoute(
    val errorType: InterviewErrorType,
) : NavKey
