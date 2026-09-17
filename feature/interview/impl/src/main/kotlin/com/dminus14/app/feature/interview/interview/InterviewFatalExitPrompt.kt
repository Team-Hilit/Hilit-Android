package com.dminus14.app.feature.interview.interview

import com.dminus14.app.core.common.modal.GlobalModalRequest
import com.dminus14.app.core.common.modal.GlobalModalResult
import com.dminus14.app.core.common.modal.showGlobalModal
import javax.inject.Inject

/** 복구할 수 없는 제출 오류를 앱 전역 Modal 계약으로 한 번 확인받는다. */
open class InterviewFatalExitPrompt
    @Inject
    constructor() {
        open suspend fun show(): GlobalModalResult =
            showGlobalModal(
                GlobalModalRequest(
                    title = "면접을 계속할 수 없어요",
                    message = "알 수 없는 오류가 발생했어요. 홈으로 이동한 뒤 다시 시도해주세요.",
                    confirmText = "홈으로 이동",
                    dismissible = false,
                ),
            )
    }
