package com.dminus14.app.feature.onboarding

internal object OnBoardingPolicy {
    const val POLL_INTERVAL_MS = 3_000L
    const val MAX_POLL_ATTEMPTS = 40

    /** 포트폴리오 업로드 진행률: 폴링 1회당 상승 폭. */
    const val PORTFOLIO_PROGRESS_STEP_PER_POLL = 10

    /** 폴링 대기 구간 진행률 캡. READY 응답 전에는 이 값을 넘지 않는다. */
    const val PORTFOLIO_PROGRESS_POLLING_CAP = 90

    /** READY 응답을 받아 업로드 완료를 표시할 때의 최종 진행률. */
    const val PORTFOLIO_PROGRESS_COMPLETE = 100

    /**
     * 세션 준비 완료 후 결과 화면으로 넘어가기 전 사용자가 완료 애니메이션
     * (초록 배경 확장 + 완료 텍스트 fadeIn/scale)을 볼 수 있게 잠시 대기한다.
     */
    const val PRELOAD_COMPLETION_DWELL_MS = 1_800L
    const val HTTPS_SCHEME = "https://"
    const val MESSAGE_LINK_FORMAT = "올바른 URL 형식이 아니에요."
    const val MESSAGE_LINK_INVALID = "공고 내용을 정리하는 데 실패했어요. 공고 내용을 직접 붙여넣어 주세요."
    const val MESSAGE_LINK_RATE_LIMIT = "공고 링크는 하루에 5번까지만 입력할 수 있어요"
    const val JD_TEXT_MIN_LENGTH = 200
    const val JD_TEXT_MAX_LENGTH = 3000
    const val MESSAGE_TEXT_TOO_SHORT = "공고 내용은 200자 이상으로 입력해 주세요"
    const val FREETEXT_MIN_LENGTH = 10
    const val FREETEXT_MAX_LENGTH = 300
    const val MESSAGE_FREETEXT_TOO_SHORT = "집중 프로젝트 설명은 10자 이상 입력해 주세요"
    const val MESSAGE_FREETEXT_NOT_RELEVANT =
        "포트폴리오에서 그 내용을 찾지 못했어요. 포트폴리오에 있는 프로젝트로 다시 적어주세요"

    /** 연관성 실패가 이 횟수에 도달하면 재선택 다이얼로그로 이스케이프 경로를 제공한다. */
    const val RELEVANCE_FAIL_ESCAPE_THRESHOLD = 4
    const val MESSAGE_PORTFOLIO_REQUIRED = "포트폴리오를 업로드해주세요"
    const val MESSAGE_PDF_SIZE = "파일이 너무 커요. 20MB 이하 PDF로 올려주세요"
    const val MESSAGE_PDF_PAGE = "페이지가 너무 많아요. 30페이지 이하 PDF로 올려주세요"
    const val MESSAGE_PDF_PASSWORD = "암호가 걸린 PDF는 열 수 없어요. 암호를 푼 PDF로 올려주세요"
    const val MESSAGE_PDF_CORRUPT = "파일이 손상된 것 같아요. 파일을 확인하고 다시 시도해 주세요"

    // 서버 status가 FAILED_FILE로 오는 케이스(스캔본·글자 30자 미만 등) 전용 안내.
    const val MESSAGE_PDF_UNREADABLE =
        "이 PDF에서 글자를 읽지 못했어요. 스캔본·이미지로 만든 PDF는 인식이 어려워요. " +
            "글자가 드래그로 선택되는 PDF로 다시 올려주세요"

    // 서버 status가 FAILED_SYSTEM/폴링 타임아웃일 때 안내.
    const val MESSAGE_PORTFOLIO_SYSTEM_FAILED =
        "포트폴리오 분석이 예상보다 오래 걸리고 있어요. 잠시 후 다시 시도해 주세요"
}
