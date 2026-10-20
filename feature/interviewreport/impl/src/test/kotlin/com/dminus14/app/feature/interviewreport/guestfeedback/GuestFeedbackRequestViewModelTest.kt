package com.dminus14.app.feature.interviewreport.guestfeedback

import com.dminus14.app.domain.exception.EmptyAttitudeAxesException
import com.dminus14.app.domain.exception.FeedbackShareAlreadyExistsException
import com.dminus14.app.domain.exception.InterviewSessionNotFoundException
import com.dminus14.app.domain.model.GuestFeedbackAxisCode
import com.dminus14.app.domain.repository.DynamicLinkRepository
import com.dminus14.app.domain.repository.FeedbackShareLocalRepository
import com.dminus14.app.domain.repository.FeedbackShareRepository
import com.dminus14.app.domain.usecase.CreateFeedbackShareDynamicLinkUseCase
import com.dminus14.app.domain.usecase.CreateFeedbackShareUseCase
import com.dminus14.app.domain.usecase.EndFeedbackShareUseCase
import com.dminus14.app.domain.usecase.GetSavedFeedbackShareTokenUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuestFeedbackRequestViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `기본값은 5개 항목이 모두 선택된 상태다`() {
        val viewModel = viewModel(FakeFeedbackShareRepository())

        assertEquals(GuestFeedbackAxisCode.entries.toSet(), viewModel.state.value.selectedAxes)
    }

    @Test
    fun `마지막 한 개 항목은 해제되지 않는다`() {
        val viewModel = viewModel(FakeFeedbackShareRepository())
        // GAZE 하나만 남기고 나머지를 모두 해제한다.
        (GuestFeedbackAxisCode.entries - GuestFeedbackAxisCode.GAZE).forEach { axis ->
            viewModel.onIntent(GuestFeedbackRequestIntent.ToggleAxis(axis))
        }
        assertEquals(setOf(GuestFeedbackAxisCode.GAZE), viewModel.state.value.selectedAxes)

        viewModel.onIntent(GuestFeedbackRequestIntent.ToggleAxis(GuestFeedbackAxisCode.GAZE))

        assertEquals(setOf(GuestFeedbackAxisCode.GAZE), viewModel.state.value.selectedAxes)
    }

    @Test
    fun `저장된 token 이 없으면 Load 후에도 종료 가능 상태가 아니다`() =
        runTest {
            val viewModel = viewModel(FakeFeedbackShareRepository())
            viewModel.onIntent(GuestFeedbackRequestIntent.Load(7L))
            advanceUntilIdle()

            assertFalse(viewModel.state.value.hasActiveShare)
        }

    @Test
    fun `Load 시 저장된 token 이 있으면 하단 버튼이 종료 상태로 바뀐다`() =
        runTest {
            val localRepository = FakeFeedbackShareLocalRepository(mapOf(7L to "saved-token"))
            val viewModel = viewModel(FakeFeedbackShareRepository(), localRepository)
            viewModel.onIntent(GuestFeedbackRequestIntent.Load(7L))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.hasActiveShare)
        }

    @Test
    fun `피드백 링크 생성 성공 시 동적 링크가 채워지고 token 을 저장한다`() =
        runTest {
            val localRepository = FakeFeedbackShareLocalRepository()
            val dynamicLinkRepository = FakeDynamicLinkRepository()
            val viewModel =
                viewModel(
                    repository = FakeFeedbackShareRepository(token = "abc123"),
                    localRepository = localRepository,
                    dynamicLinkRepository = dynamicLinkRepository,
                )
            viewModel.onIntent(GuestFeedbackRequestIntent.Load(7L))

            viewModel.onIntent(GuestFeedbackRequestIntent.ClickSubmit)
            advanceUntilIdle()

            assertEquals("hilit://feedback/abc123", dynamicLinkRepository.requestedDeepLink)
            assertEquals(
                "https://short.link/hilit://feedback/abc123",
                viewModel.state.value.shareLink,
            )
            assertTrue(viewModel.state.value.hasActiveShare)
            assertEquals("abc123", localRepository.getToken(7L))
        }

    @Test
    fun `동적 링크 생성이 실패해도 원시 딥링크로 공유 링크가 채워진다`() =
        runTest {
            val dynamicLinkRepository = FakeDynamicLinkRepository(shouldFail = true)
            val viewModel =
                viewModel(
                    repository = FakeFeedbackShareRepository(token = "abc123"),
                    dynamicLinkRepository = dynamicLinkRepository,
                )
            viewModel.onIntent(GuestFeedbackRequestIntent.Load(7L))

            viewModel.onIntent(GuestFeedbackRequestIntent.ClickSubmit)
            advanceUntilIdle()

            assertEquals("hilit://feedback/abc123", viewModel.state.value.shareLink)
            assertTrue(viewModel.state.value.hasActiveShare)
        }

    @Test
    fun `종료 가능 상태에서 제출하면 공유를 종료하고 저장된 token 을 지운 뒤 리포트로 돌아간다`() =
        runTest {
            val localRepository = FakeFeedbackShareLocalRepository(mapOf(7L to "saved-token"))
            val repository = FakeFeedbackShareRepository()
            val viewModel = viewModel(repository, localRepository)
            viewModel.onIntent(GuestFeedbackRequestIntent.Load(7L))
            advanceUntilIdle()

            val effects = mutableListOf<GuestFeedbackRequestEffect>()
            val job = TestScope(dispatcher).launch { viewModel.effect.collect { effects += it } }

            viewModel.onIntent(GuestFeedbackRequestIntent.ClickSubmit)
            advanceUntilIdle()

            assertTrue(repository.closeCalled)
            assertNull(localRepository.getToken(7L))
            assertFalse(viewModel.state.value.hasActiveShare)
            assertTrue(effects.any { it is GuestFeedbackRequestEffect.NavigateBack })
            job.cancel()
        }

    @Test
    fun `평가 항목 오류는 토스트로 안내하고 화면에 남는다`() =
        runTest {
            val repository =
                FakeFeedbackShareRepository(
                    createFailure =
                        EmptyAttitudeAxesException(
                            errCode = "EMPTY_ATTITUDE_AXES",
                            message = "평가 항목을 최소 1개 선택해 주세요.",
                        ),
                )
            val viewModel = viewModel(repository)
            viewModel.onIntent(GuestFeedbackRequestIntent.Load(7L))

            val effects = mutableListOf<GuestFeedbackRequestEffect>()
            val job = TestScope(dispatcher).launch { viewModel.effect.collect { effects += it } }

            viewModel.onIntent(GuestFeedbackRequestIntent.ClickSubmit)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.submitting)
            assertFalse(viewModel.state.value.hasActiveShare)
            assertTrue(
                effects.any {
                    it is GuestFeedbackRequestEffect.ShowToast &&
                        it.message == "평가 항목을 최소 1개 선택해 주세요."
                },
            )
            assertFalse(effects.any { it is GuestFeedbackRequestEffect.NavigateBack })
            job.cancel()
        }

    @Test
    fun `서버에 이미 활성 링크가 있으면 종료 가능 상태로 바뀐다`() =
        runTest {
            val repository =
                FakeFeedbackShareRepository(
                    createFailure =
                        FeedbackShareAlreadyExistsException(
                            errCode = "FEEDBACK_SHARE_ALREADY_EXISTS",
                            message = "이미 피드백 요청 링크가 있어요.",
                        ),
                )
            val viewModel = viewModel(repository)
            viewModel.onIntent(GuestFeedbackRequestIntent.Load(7L))

            viewModel.onIntent(GuestFeedbackRequestIntent.ClickSubmit)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.submitting)
            assertTrue(viewModel.state.value.hasActiveShare)
        }

    @Test
    fun `서버에 이미 활성 링크가 있고 로컬에 실제 token 이 남아있으면 그 token 으로 링크를 복구한다`() =
        runTest {
            // 초기 조회 뒤 다른 요청이 실제 token 을 저장한 경합을 재현한다.
            // 생성 충돌 응답에는 token 이 없으므로 저장된 값으로 링크를 복구해야 한다.
            val localRepository = FakeFeedbackShareLocalRepository()
            val dynamicLinkRepository = FakeDynamicLinkRepository()
            val repository =
                FakeFeedbackShareRepository(
                    createFailure =
                        FeedbackShareAlreadyExistsException(
                            errCode = "FEEDBACK_SHARE_ALREADY_EXISTS",
                            message = "이미 피드백 요청 링크가 있어요.",
                        ),
                )
            val viewModel =
                viewModel(
                    repository = repository,
                    localRepository = localRepository,
                    dynamicLinkRepository = dynamicLinkRepository,
                )
            viewModel.onIntent(GuestFeedbackRequestIntent.Load(7L))
            advanceUntilIdle()
            localRepository.saveToken(7L, "already-saved")

            viewModel.onIntent(GuestFeedbackRequestIntent.ClickSubmit)
            advanceUntilIdle()

            assertEquals("hilit://feedback/already-saved", dynamicLinkRepository.requestedDeepLink)
            assertEquals(
                "https://short.link/hilit://feedback/already-saved",
                viewModel.state.value.shareLink,
            )
            assertFalse(viewModel.state.value.submitting)
            assertTrue(viewModel.state.value.hasActiveShare)
        }

    @Test
    fun `면접 세션을 찾을 수 없으면 안내 후 리포트로 돌아간다`() =
        runTest {
            val repository =
                FakeFeedbackShareRepository(
                    createFailure =
                        InterviewSessionNotFoundException(
                            errCode = "INTERVIEW_SESSION_NOT_FOUND",
                            message = "면접 세션을 찾을 수 없어요.",
                        ),
                )
            val viewModel = viewModel(repository)
            viewModel.onIntent(GuestFeedbackRequestIntent.Load(7L))

            val effects = mutableListOf<GuestFeedbackRequestEffect>()
            val job = TestScope(dispatcher).launch { viewModel.effect.collect { effects += it } }

            viewModel.onIntent(GuestFeedbackRequestIntent.ClickSubmit)
            advanceUntilIdle()

            assertTrue(effects.any { it is GuestFeedbackRequestEffect.NavigateBack })
            job.cancel()
        }

    private fun viewModel(
        repository: FeedbackShareRepository,
        localRepository: FeedbackShareLocalRepository = FakeFeedbackShareLocalRepository(),
        dynamicLinkRepository: DynamicLinkRepository = FakeDynamicLinkRepository(),
    ): GuestFeedbackRequestViewModel =
        GuestFeedbackRequestViewModel(
            CreateFeedbackShareUseCase(repository, localRepository),
            EndFeedbackShareUseCase(repository, localRepository),
            GetSavedFeedbackShareTokenUseCase(localRepository),
            CreateFeedbackShareDynamicLinkUseCase(dynamicLinkRepository),
        )
}

private class FakeFeedbackShareRepository(
    private val token: String = "token",
    private val createFailure: Throwable? = null,
) : FeedbackShareRepository {
    var closeCalled: Boolean = false
        private set

    override suspend fun createShare(
        sessionId: Long,
        axes: List<GuestFeedbackAxisCode>,
    ): String {
        createFailure?.let { throw it }
        return token
    }

    override suspend fun closeShare(sessionId: Long) {
        closeCalled = true
    }
}

private class FakeDynamicLinkRepository(
    private val shouldFail: Boolean = false,
) : DynamicLinkRepository {
    var requestedDeepLink: String? = null
        private set

    override suspend fun createLink(deepLink: String): String {
        requestedDeepLink = deepLink
        if (shouldFail) error("동적 링크 생성 실패")
        return "https://short.link/$deepLink"
    }
}

private class FakeFeedbackShareLocalRepository(
    initialTokens: Map<Long, String> = emptyMap(),
) : FeedbackShareLocalRepository {
    private val tokens = initialTokens.toMutableMap()

    override suspend fun getToken(sessionId: Long): String? = tokens[sessionId]

    override suspend fun saveToken(
        sessionId: Long,
        token: String,
    ) {
        tokens[sessionId] = token
    }

    override suspend fun clearToken(sessionId: Long) {
        tokens.remove(sessionId)
    }
}
