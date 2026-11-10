package com.dminus14.app.data.remote.authenticator

import com.dminus14.app.data.remote.mapper.ApiErrorCode
import com.dminus14.app.domain.exception.NetworkUnavailableException
import com.dminus14.app.domain.exception.SessionException
import com.dminus14.app.domain.model.AuthSession
import com.dminus14.app.domain.repository.SessionRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class TokenAuthenticatorTest {
    @Test
    fun `TOKEN_EXPIRED 401이면 refresh 후 새 AccessToken으로 재요청한다`() {
        val sessionRepository =
            FakeSessionRepository(
                session = AuthSession(accessToken = OLD_ACCESS_TOKEN, refreshToken = REFRESH_TOKEN),
                refreshedSession =
                    AuthSession(
                        accessToken = NEW_ACCESS_TOKEN,
                        refreshToken = NEW_REFRESH_TOKEN,
                    ),
            )
        val client = createClient(sessionRepository)

        MockWebServer().use { server ->
            server.enqueue(unauthorized(ApiErrorCode.TOKEN_EXPIRED))
            server.enqueue(MockResponse().setResponseCode(204))

            client
                .newCall(authorizedRequest(server.url("/token-expired").toString()))
                .execute()
                .use { response -> assertEquals(204, response.code) }

            val first = server.takeRequest()
            val second = server.takeRequest()

            assertEquals("Bearer $OLD_ACCESS_TOKEN", first.getHeader(HEADER_AUTHORIZATION))
            assertEquals("Bearer $NEW_ACCESS_TOKEN", second.getHeader(HEADER_AUTHORIZATION))
            assertEquals(1, sessionRepository.refreshCount)
            assertEquals(0, sessionRepository.clearCount)
        }
    }

    @Test
    fun `INVALID_TOKEN 401이면 refresh 후 재요청한다`() {
        val sessionRepository =
            FakeSessionRepository(
                session = AuthSession(accessToken = OLD_ACCESS_TOKEN, refreshToken = REFRESH_TOKEN),
                refreshedSession =
                    AuthSession(
                        accessToken = NEW_ACCESS_TOKEN,
                        refreshToken = NEW_REFRESH_TOKEN,
                    ),
            )
        val client = createClient(sessionRepository)

        MockWebServer().use { server ->
            server.enqueue(unauthorized(ApiErrorCode.INVALID_TOKEN))
            server.enqueue(MockResponse().setResponseCode(204))

            client
                .newCall(authorizedRequest(server.url("/invalid-token").toString()))
                .execute()
                .use { response -> assertEquals(204, response.code) }

            server.takeRequest()
            val retry = server.takeRequest()

            assertEquals("Bearer $NEW_ACCESS_TOKEN", retry.getHeader(HEADER_AUTHORIZATION))
            assertEquals(1, sessionRepository.refreshCount)
        }
    }

    @Test
    fun `이미 갱신된 AccessToken이 있으면 refresh 없이 재시도한다`() {
        val sessionRepository =
            FakeSessionRepository(
                session = AuthSession(accessToken = NEW_ACCESS_TOKEN, refreshToken = REFRESH_TOKEN),
            )
        val client = createClient(sessionRepository)

        MockWebServer().use { server ->
            server.enqueue(unauthorized(ApiErrorCode.TOKEN_EXPIRED))
            server.enqueue(MockResponse().setResponseCode(204))

            client
                .newCall(authorizedRequest(server.url("/already-refreshed").toString()))
                .execute()
                .use { response -> assertEquals(204, response.code) }

            server.takeRequest()
            val retry = server.takeRequest()

            assertEquals("Bearer $NEW_ACCESS_TOKEN", retry.getHeader(HEADER_AUTHORIZATION))
            assertEquals(0, sessionRepository.refreshCount)
        }
    }

    @Test
    fun `refresh가 SessionException이면 세션을 지우고 재요청하지 않는다`() {
        val sessionRepository =
            FakeSessionRepository(
                session = AuthSession(accessToken = OLD_ACCESS_TOKEN, refreshToken = REFRESH_TOKEN),
                refreshFailure =
                    SessionException(
                        errCode = ApiErrorCode.LOGIN_EXPIRED,
                        message = "로그인 세션이 만료되었습니다.",
                    ),
            )
        val client = createClient(sessionRepository)

        MockWebServer().use { server ->
            server.enqueue(unauthorized(ApiErrorCode.TOKEN_EXPIRED))

            client
                .newCall(authorizedRequest(server.url("/login-expired").toString()))
                .execute()
                .use { response -> assertEquals(401, response.code) }

            assertEquals(1, server.requestCount)
            assertEquals(1, sessionRepository.refreshCount)
            assertEquals(1, sessionRepository.clearCount)
            assertNull(sessionRepository.session)
        }
    }

    @Test
    fun `refresh가 네트워크 오류면 세션을 유지한 채 재요청하지 않고 IOException으로 실패한다`() {
        val sessionRepository =
            FakeSessionRepository(
                session = AuthSession(accessToken = OLD_ACCESS_TOKEN, refreshToken = REFRESH_TOKEN),
                refreshFailure =
                    NetworkUnavailableException(
                        errCode = ApiErrorCode.NETWORK_UNAVAILABLE,
                    ),
            )
        val client = createClient(sessionRepository)

        MockWebServer().use { server ->
            server.enqueue(unauthorized(ApiErrorCode.TOKEN_EXPIRED))

            assertThrows(IOException::class.java) {
                client
                    .newCall(authorizedRequest(server.url("/refresh-network-error").toString()))
                    .execute()
            }

            assertEquals(1, server.requestCount)
            assertEquals(1, sessionRepository.refreshCount)
            assertEquals(0, sessionRepository.clearCount)
            assertEquals(OLD_ACCESS_TOKEN, sessionRepository.session?.accessToken)
        }
    }

    @Test
    fun `세션이 없으면 refresh하지 않고 재요청하지 않는다`() {
        val sessionRepository = FakeSessionRepository(session = null)
        val client = createClient(sessionRepository)

        MockWebServer().use { server ->
            server.enqueue(unauthorized(ApiErrorCode.TOKEN_EXPIRED))

            client
                .newCall(authorizedRequest(server.url("/no-session").toString()))
                .execute()
                .use { response -> assertEquals(401, response.code) }

            assertEquals(1, server.requestCount)
            assertEquals(0, sessionRepository.refreshCount)
            assertEquals(0, sessionRepository.clearCount)
        }
    }

    @Test
    fun `에러 코드가 null이거나 파싱 실패면 Bearer가 있어도 refresh하지 않는다`() {
        val sessionRepository =
            FakeSessionRepository(
                session = AuthSession(accessToken = OLD_ACCESS_TOKEN, refreshToken = REFRESH_TOKEN),
            )
        val client = createClient(sessionRepository)

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("not-json"))

            client
                .newCall(authorizedRequest(server.url("/unparseable-body").toString()))
                .execute()
                .use { response -> assertEquals(401, response.code) }

            assertEquals(1, server.requestCount)
            assertEquals(0, sessionRepository.refreshCount)
        }
    }

    @Test
    fun `재시도 한도를 넘기면 refresh를 중단한다`() {
        val sessionRepository =
            FakeSessionRepository(
                session = AuthSession(accessToken = OLD_ACCESS_TOKEN, refreshToken = REFRESH_TOKEN),
                refreshedSession =
                    AuthSession(
                        accessToken = OLD_ACCESS_TOKEN,
                        refreshToken = REFRESH_TOKEN,
                    ),
            )
        val client = createClient(sessionRepository)

        MockWebServer().use { server ->
            repeat(4) { server.enqueue(unauthorized(ApiErrorCode.TOKEN_EXPIRED)) }

            client
                .newCall(authorizedRequest(server.url("/retry-limit").toString()))
                .execute()
                .use { response -> assertEquals(401, response.code) }

            // count=1,2 일 때만 refresh 시도 → 요청 3회, refresh 2회 후 중단
            assertEquals(3, server.requestCount)
            assertEquals(2, sessionRepository.refreshCount)
        }
    }

    @Test
    fun `401이 아니면 Authenticator가 동작하지 않는다`() {
        val sessionRepository =
            FakeSessionRepository(
                session = AuthSession(accessToken = OLD_ACCESS_TOKEN, refreshToken = REFRESH_TOKEN),
            )
        val client = createClient(sessionRepository)

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))

            client
                .newCall(authorizedRequest(server.url("/server-error").toString()))
                .execute()
                .use { response -> assertEquals(500, response.code) }

            assertEquals(1, server.requestCount)
            assertEquals(0, sessionRepository.refreshCount)
        }
    }

    @Test
    fun `재발급 대상이 아닌 401 코드면 refresh하지 않는다`() {
        val sessionRepository =
            FakeSessionRepository(
                session = AuthSession(accessToken = OLD_ACCESS_TOKEN, refreshToken = REFRESH_TOKEN),
            )
        val client = createClient(sessionRepository)

        MockWebServer().use { server ->
            server.enqueue(unauthorized(ApiErrorCode.SOCIAL_LOGIN_FAILED))

            client
                .newCall(authorizedRequest(server.url("/social-login-failed").toString()))
                .execute()
                .use { response -> assertEquals(401, response.code) }

            assertEquals(1, server.requestCount)
            assertEquals(0, sessionRepository.refreshCount)
        }
    }

    private fun createClient(sessionRepository: SessionRepository): OkHttpClient =
        OkHttpClient
            .Builder()
            .authenticator(TokenAuthenticator(sessionRepository))
            .build()

    private fun authorizedRequest(url: String): Request =
        Request
            .Builder()
            .url(url)
            .header(HEADER_AUTHORIZATION, "Bearer $OLD_ACCESS_TOKEN")
            .build()

    private fun unauthorized(code: String): MockResponse =
        MockResponse()
            .setResponseCode(401)
            .setBody(
                """
                {"success":false,"code":"$code","message":"synthetic"}
                """.trimIndent(),
            )

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val OLD_ACCESS_TOKEN = "old-access-token"
        const val NEW_ACCESS_TOKEN = "new-access-token"
        const val REFRESH_TOKEN = "refresh-token"
        const val NEW_REFRESH_TOKEN = "new-refresh-token"
    }
}

internal class FakeSessionRepository(
    session: AuthSession? = null,
    private val refreshedSession: AuthSession? = null,
    private val refreshFailure: Throwable? = null,
) : SessionRepository {
    var session: AuthSession? = session
        private set

    var refreshCount: Int = 0
        private set

    var clearCount: Int = 0
        private set

    override suspend fun getAuthSession(): AuthSession? = session

    override suspend fun refreshToken(refreshToken: String): AuthSession {
        refreshCount += 1
        refreshFailure?.let { throw it }
        val next =
            refreshedSession
                ?: error("refreshedSession이 설정되지 않았습니다.")
        session = next
        return next
    }

    override suspend fun saveAuthSession(
        accessToken: String,
        refreshToken: String,
    ): AuthSession {
        val saved = AuthSession(accessToken = accessToken, refreshToken = refreshToken)
        session = saved
        return saved
    }

    override suspend fun clearAuthSession() {
        clearCount += 1
        session = null
    }
}
