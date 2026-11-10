package com.dminus14.app.domain.usecase

import com.dminus14.app.domain.repository.DynamicLinkRepository
import com.dminus14.app.domain.util.runCatchingCancellable
import javax.inject.Inject

/**
 * 지인 피드백 공유 token 을 딥링크(`hilit://feedback/{token}`)로 조립하고, 동적 링크로
 * 변환해 반환한다.
 *
 * scheme(`hilit`)/host(`feedback`)는 `app` 모듈 `MainActivity`의
 * `FEEDBACK_SHARE_DEEP_LINK_SCHEME`/`FEEDBACK_SHARE_DEEP_LINK_HOST`와 반드시 같은 값이어야
 * 링크를 탭했을 때 앱이 열린다.
 *
 * [DynamicLinkRepository] 호출이 실패해도(네트워크 오류, 미확정 도메인 등) 원시 딥링크 자체는
 * 앱의 커스텀 scheme intent-filter로 그대로 열리므로, 실패를 사용자에게 노출하지 않고 원시
 * 딥링크로 조용히 대체한다. [EndFeedbackShareUseCase]의 self-heal과 달리 실패 원인을 가리지
 * 않고 항상 대체하는 이유가 이것이다 — 대체값이 어떤 실패에도 유효하기 때문이다.
 */
class CreateFeedbackShareDynamicLinkUseCase
    @Inject
    constructor(
        private val dynamicLinkRepository: DynamicLinkRepository,
    ) {
        suspend operator fun invoke(token: String): Result<String> {
            val deepLink = buildDeepLink(token)
            return runCatchingCancellable {
                dynamicLinkRepository.createLink(deepLink)
            }.recoverCatching { deepLink }
        }

        /**
         * [link]가 동적 링크 생성 실패로 대체된 원시 딥링크인지 판단한다.
         *
         * 대체값은 일시적인 실패의 결과이므로 기기에 저장해 계속 재사용하면 안 된다. 저장하는 쪽은
         * 이 값으로 걸러, 다음 조회 때 동적 링크를 다시 시도할 수 있게 한다.
         */
        fun isFallbackLink(
            token: String,
            link: String,
        ): Boolean = link == buildDeepLink(token)

        private fun buildDeepLink(token: String): String =
            "$FEEDBACK_SHARE_DEEP_LINK_SCHEME://$FEEDBACK_SHARE_DEEP_LINK_HOST/$token"

        private companion object {
            const val FEEDBACK_SHARE_DEEP_LINK_SCHEME = "hilit"
            const val FEEDBACK_SHARE_DEEP_LINK_HOST = "feedback"
        }
    }
