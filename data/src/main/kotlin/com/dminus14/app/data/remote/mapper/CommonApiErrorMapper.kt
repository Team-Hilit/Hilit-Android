package com.dminus14.app.data.remote.mapper

import com.dminus14.app.data.remote.authenticator.TokenRefreshServerFailureException
import com.dminus14.app.data.remote.dto.common.ApiErrorResponseDto
import com.dminus14.app.domain.exception.CustomException
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.ServerException
import com.dminus14.app.domain.exception.UnknownException
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 전송·HTTP 계층의 공통 오류를 domain [CustomException]으로 변환한다.
 *
 * API별 비즈니스 코드는 [mapBusiness]에서만 처리하고, 인식하지 못하면 `null`을 반환한다.
 * 공통 정책(Network / 5xx / Unknown)은 이 객체가 담당한다.
 *
 * [CancellationException]은 JVM에서 [IllegalStateException]의 하위 타입이므로, domain 예외로
 * 변환하지 않고 그대로 다시 던져 구조적 동시성의 취소 전파를 보존한다.
 *
 * 토큰 재발급이 서버 오류로 실패해 [TokenRefreshServerFailureException]으로 전달된 경우는 네트워크
 * 오류가 아니므로 감싼 [ServerException]을 그대로 돌려준다.
 */
internal object CommonApiErrorMapper {
    fun map(
        error: Throwable,
        mapBusiness: (HttpException, ApiErrorResponseDto?) -> CustomException? = { _, _ -> null },
    ): Throwable =
        when (error) {
            is CancellationException -> {
                throw error
            }

            is TokenRefreshServerFailureException -> {
                error.serverException
            }

            is IOException -> {
                NetworkUnavailableException(
                    errCode = ApiErrorCode.NETWORK_UNAVAILABLE,
                    cause = error,
                )
            }

            is HttpException -> {
                mapHttpException(error, mapBusiness)
            }

            is IllegalStateException -> {
                UnknownException(
                    errCode = ApiErrorCode.UNKNOWN,
                    message = error.message ?: DEFAULT_UNKNOWN_MESSAGE,
                    cause = error,
                )
            }

            else -> {
                error
            }
        }

    private fun mapHttpException(
        error: HttpException,
        mapBusiness: (HttpException, ApiErrorResponseDto?) -> CustomException?,
    ): CustomException {
        val apiError = ApiErrorBodyParser.parse(error)
        mapBusiness(error, apiError)?.let { return it }

        val message = apiError?.message.orEmpty()
        return when (error.code()) {
            in HTTP_SERVER_ERROR_RANGE -> {
                ServerException(
                    errCode = apiError?.code ?: ApiErrorCode.SERVER_ERROR,
                    cause = error,
                )
            }

            else -> {
                UnknownException(
                    errCode = apiError?.code ?: ApiErrorCode.UNKNOWN,
                    message = message.ifBlank { DEFAULT_UNKNOWN_MESSAGE },
                    cause = error,
                )
            }
        }
    }

    private const val DEFAULT_UNKNOWN_MESSAGE = "알 수 없는 오류가 발생했습니다."
    private val HTTP_SERVER_ERROR_RANGE = 500..599
}
