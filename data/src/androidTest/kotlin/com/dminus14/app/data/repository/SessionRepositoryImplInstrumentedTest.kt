package com.dminus14.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dminus14.app.core.crypto.CryptoManager
import com.dminus14.app.data.local.DataStoreKeys
import com.dminus14.app.data.local.datasource.LocalDataSourceImpl
import com.dminus14.app.data.local.datasource.PreferenceEdit
import com.dminus14.app.data.remote.auth.AccessTokenProvider
import com.dminus14.app.data.remote.datasource.AuthRemoteDataSource
import com.dminus14.app.data.remote.dto.auth.SocialLoginResponseDto
import com.dminus14.app.data.remote.dto.auth.TokenRefreshResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 DataStore + Android Keystore [CryptoManager] + [AccessTokenProvider] 캐시 연동을 검증한다.
 *
 * [TokenAuthenticatorTest]가 Authenticator 단위를 다루는 것과 달리,
 * 세션 영속화·복호화·캐시 동기화의 기기 의존 경로를 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryImplInstrumentedTest {
    private lateinit var localDataSource: LocalDataSourceImpl
    private lateinit var cryptoManager: CryptoManager
    private lateinit var accessTokenProvider: AccessTokenProvider
    private lateinit var authRemoteDataSource: FakeAuthRemoteDataSource
    private lateinit var repository: SessionRepositoryImpl

    @Before
    fun setUp() =
        runTest {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            localDataSource = LocalDataSourceImpl(context)
            cryptoManager = CryptoManager()
            accessTokenProvider = AccessTokenProvider(localDataSource, cryptoManager)
            authRemoteDataSource = FakeAuthRemoteDataSource()
            repository =
                SessionRepositoryImpl(
                    authRemoteDataSource = authRemoteDataSource,
                    localDataSource = localDataSource,
                    cryptoManager = cryptoManager,
                    accessTokenProvider = accessTokenProvider,
                )
            repository.clearAuthSession()
        }

    @Test
    fun `save 후 get하면 동일 토큰을 복호화해 반환한다`() =
        runTest {
            repository.saveAuthSession(
                accessToken = ACCESS_TOKEN,
                refreshToken = REFRESH_TOKEN,
            )

            val session = repository.getAuthSession()

            assertEquals(ACCESS_TOKEN, session?.accessToken)
            assertEquals(REFRESH_TOKEN, session?.refreshToken)
        }

    @Test
    fun `refresh 후 access와 refresh가 함께 교체된다`() =
        runTest {
            repository.saveAuthSession(
                accessToken = ACCESS_TOKEN,
                refreshToken = REFRESH_TOKEN,
            )
            authRemoteDataSource.refreshResponse =
                TokenRefreshResponseDto(
                    accessToken = NEW_ACCESS_TOKEN,
                    refreshToken = NEW_REFRESH_TOKEN,
                )

            val refreshed = repository.refreshToken(REFRESH_TOKEN)
            val persisted = repository.getAuthSession()

            assertEquals(NEW_ACCESS_TOKEN, refreshed.accessToken)
            assertEquals(NEW_REFRESH_TOKEN, refreshed.refreshToken)
            assertEquals(NEW_ACCESS_TOKEN, persisted?.accessToken)
            assertEquals(NEW_REFRESH_TOKEN, persisted?.refreshToken)
            assertEquals(NEW_ACCESS_TOKEN, accessTokenProvider.get())
        }

    @Test
    fun `복호화할 수 없는 세션은 clear 후 null을 반환한다`() =
        runTest {
            localDataSource.editAtomically(
                listOf(
                    PreferenceEdit.Set(
                        DataStoreKeys.Auth.ACCESS_TOKEN,
                        CORRUPT_CIPHERTEXT,
                    ),
                    PreferenceEdit.Set(
                        DataStoreKeys.Auth.REFRESH_TOKEN,
                        CORRUPT_CIPHERTEXT,
                    ),
                ),
            )
            accessTokenProvider.set(ACCESS_TOKEN)

            val session = repository.getAuthSession()

            assertNull(session)
            assertNull(localDataSource.get(DataStoreKeys.Auth.ACCESS_TOKEN))
            assertNull(localDataSource.get(DataStoreKeys.Auth.REFRESH_TOKEN))
            assertNull(accessTokenProvider.get())
        }

    @Test
    fun `save와 clear는 AccessTokenProvider 캐시를 동기화한다`() =
        runTest {
            repository.saveAuthSession(
                accessToken = ACCESS_TOKEN,
                refreshToken = REFRESH_TOKEN,
            )
            assertEquals(ACCESS_TOKEN, accessTokenProvider.get())

            repository.clearAuthSession()

            assertNull(accessTokenProvider.get())
            assertNull(repository.getAuthSession())
        }

    private class FakeAuthRemoteDataSource(
        var refreshResponse: TokenRefreshResponseDto? = null,
    ) : AuthRemoteDataSource {
        override suspend fun loginWithKakao(credential: String): SocialLoginResponseDto =
            error("loginWithKakao is not used in SessionRepositoryImplInstrumentedTest")

        override suspend fun refreshToken(refreshToken: String): TokenRefreshResponseDto =
            refreshResponse
                ?: error("refreshResponse must be set before refreshToken()")

        override suspend fun logout() = Unit
    }

    private companion object {
        const val ACCESS_TOKEN = "instrumented-access-token"
        const val REFRESH_TOKEN = "instrumented-refresh-token"
        const val NEW_ACCESS_TOKEN = "instrumented-new-access-token"
        const val NEW_REFRESH_TOKEN = "instrumented-new-refresh-token"

        /** 형식 자체가 잘못된 페이로드 — Keystore 복호화 전에 IllegalArgumentException을 유발한다. */
        const val CORRUPT_CIPHERTEXT = "not-a-valid-ciphertext"
    }
}
