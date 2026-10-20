package com.dminus14.app.feature.onboarding

import com.dminus14.app.core.common.mvi.MviEffect
import com.dminus14.app.core.common.mvi.MviIntent
import com.dminus14.app.core.common.mvi.MviState
import java.io.File

sealed interface OnBoardingInterviewIntent : MviIntent {
    sealed interface Flow : OnBoardingInterviewIntent {
        data object Load : Flow

        data object ClickClose : Flow

        data object ClickSkip : Flow

        data object ClickContinue : Flow

        data object ClickPrevious : Flow

        data class MainProjectTextChange(
            val value: String,
        ) : Flow
    }

    sealed interface JobDescription : OnBoardingInterviewIntent {
        data class JobDescriptionTabChange(
            val index: Int,
        ) : JobDescription

        data class JobDescriptionLinkChange(
            val value: String,
        ) : JobDescription

        data class JobDescriptionTextChange(
            val value: String,
        ) : JobDescription
    }

    sealed interface Portfolio : OnBoardingInterviewIntent {
        data object ClickPortfolioUpload : Portfolio

        data object ClickPortfolioRemove : Portfolio

        /** Portfolio 스텝 진입 시 화면이 발화한다. ViewModel 가드로 세션당 1회만 처리된다. */
        data object PortfolioStepEntered : Portfolio

        /** Case1: 기존 포트폴리오 안내 팝업 확인. */
        data object ClickExistingPortfolioConfirm : Portfolio

        /** Case2: 안내 팝업 배경/본문 탭 dismiss. */
        data object ClickAutoDismissNotice : Portfolio

        /** Case2: 3초 자동 dismiss. */
        data object AutoDismissNoticeTimedOut : Portfolio

        /** SAF 파일 선택기가 PDF를 캐시로 복사해 돌려준 결과. */
        data class PortfolioFileSelected(
            val file: File,
            val fileName: String,
        ) : Portfolio
    }

    sealed interface SessionPreparation : OnBoardingInterviewIntent {
        /**
         * S3.5 연관성 판단이 4회 연속 실패했을 때 뜨는 다이얼로그의 선택지 중
         * "포트폴리오 다시 올리기"를 눌렀을 때 발생한다.
         */
        data object ClickRelevanceRetryPortfolio : SessionPreparation

        /**
         * S3.5 연관성 판단이 4회 연속 실패했을 때 뜨는 다이얼로그의 선택지 중
         * "집중 프로젝트 없이 진행"을 눌렀을 때 발생한다.
         */
        data object ClickRelevanceProceedWithoutMainProject : SessionPreparation

        /**
         * Preload(세션 생성/준비) 실패 다이얼로그의 확인 버튼. 다이얼로그를 닫고 사용자를
         * 이전 스텝(MainProject)으로 되돌려 재시도 가능하게 만든다.
         */
        data object ClickPreloadFailureAcknowledged : SessionPreparation
    }
}

enum class ExistingPortfolioModalPhase {
    None,
    ConfirmContinue,
    AutoDismissNotice,
}

enum class OnBoardingInterviewStep {
    JobDescription,
    Portfolio,
    MainProject,
    Preload,
}

enum class JobDescriptionTab {
    Link,
    Text,
}

/** JD 링크 입력 필드의 비동기 검증 상태. */
enum class JdLinkStatus {
    /** 입력 전(비어 있음) 또는 스킴을 아직 입력 중. */
    Idle,

    /** URL 검증 API 호출 중. */
    Validating,

    /** 검증 성공. */
    Valid,

    /** 포맷 오류 또는 검증 실패. */
    Invalid,
}

/** [OnBoardingLoadingStepRow]의 진행 상태. */
enum class OnBoardingLoadingStepStatus {
    Waiting,
    InProgress,
    Completed,
}

data class OnBoardingInterviewState(
    val step: OnBoardingInterviewStep = OnBoardingInterviewStep.JobDescription,
    val jobDescriptionTab: JobDescriptionTab = JobDescriptionTab.Link,
    val jobDescriptionLink: String = "",
    val jdLinkStatus: JdLinkStatus = JdLinkStatus.Idle,
    val jdLinkSubText: String = "",
    val jobDescriptionText: String = "",
    /** 직접 입력(Text) 탭의 검증 에러 메시지. 표기 방식은 디자이너 협의 예정이라 저장만 한다. */
    val jdTextError: String? = null,
    val portfolioFileName: String? = null,
    val existingPortfolioModalPhase: ExistingPortfolioModalPhase = ExistingPortfolioModalPhase.None,
    /** 기존 포폴 안내 모달/팝업을 이번 온보딩 세션에서 이미 노출했는지. */
    val hasShownExistingPortfolioNotice: Boolean = false,
    /** 이번 달 삭제 기회 잔여 여부. AutoDismissNotice 안내 문구의 잔여 횟수 표기에 사용. */
    val existingDeleteAvailable: Boolean = false,
    /** 포트폴리오 스텝 인라인 에러(필수 누락·PDF 검증 실패 등). null이면 숨긴다. */
    val portfolioErrorMessage: String? = null,
    val isPortfolioProcessing: Boolean = false,
    /**
     * 포트폴리오 업로드 진행률(0~100). 폴링 1회당 10%씩 상승하고 서버가 READY 를 반환하면 100.
     * 폴링이 최대치를 넘어 타임아웃되는 구간에서는 90에서 캡해 완료로 오해되지 않도록 한다.
     */
    val portfolioUploadProgress: Int = 0,
    val mainProjectText: String = "",
    /** 집중 프로젝트 입력의 검증 에러 메시지. 표기 방식은 디자이너 협의 예정이라 저장만 한다. */
    val mainProjectError: String? = null,
    /**
     * S3.5 서버 연관성 판단(`FREETEXT_NOT_RELEVANT`) 실패가 이 세션에서 몇 번 발생했는지.
     * 4회째부터는 [showRelevanceFailDialog]가 뜨고, 그 전엔 인라인 에러로만 안내한다.
     */
    val mainProjectRelevanceFailCount: Int = 0,
    /**
     * 연관성 판단 4회 연속 실패 시 재선택 다이얼로그(포폴 다시 올리기 / 집중 프로젝트 없이 진행)를 띄운다.
     */
    val showRelevanceFailDialog: Boolean = false,
    val errorMessage: String? = null,
    val loadingBasicInfo: OnBoardingLoadingStepStatus = OnBoardingLoadingStepStatus.Waiting,
    val loadingJd: OnBoardingLoadingStepStatus = OnBoardingLoadingStepStatus.Waiting,
    val loadingPortfolio: OnBoardingLoadingStepStatus = OnBoardingLoadingStepStatus.Waiting,
) : MviState

sealed interface OnBoardingInterviewEffect : MviEffect {
    data object CloseRequested : OnBoardingInterviewEffect

    /** 포트폴리오 PDF를 고르기 위해 SAF 문서 선택기를 연다. */
    data object LaunchPortfolioPicker : OnBoardingInterviewEffect

    /** 면접 세션이 준비(READY)되어 결과 화면으로 이동한다. */
    data class NavigateToResult(
        val sessionId: Long,
    ) : OnBoardingInterviewEffect

    /** 어뷰징 탐지로 면접 시작이 제한되어(`ACCOUNT_SUSPENDED`) 이용 제한 안내 화면으로 이동한다. */
    data object NavigateToSuspensionNotice : OnBoardingInterviewEffect
}
