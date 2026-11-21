package com.dminus14.app.data.remote.authenticator

import com.dminus14.app.domain.exception.ServerException
import java.io.IOException

/**
 * 토큰 재발급이 서버 오류([ServerException])로 실패했음을 원래 요청에 전달하는 [IOException].
 *
 * OkHttp [okhttp3.Authenticator]는 [IOException]만 호출부로 전파할 수 있으므로 서버 오류를 이 타입으로
 * 감싸 던지고, [com.dminus14.app.data.remote.mapper.CommonApiErrorMapper]가 [serverException]으로
 * 되돌려 일반 네트워크 오류와 구분한다.
 */
internal class TokenRefreshServerFailureException(
    val serverException: ServerException,
) : IOException(serverException.message, serverException)
