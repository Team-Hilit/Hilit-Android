package com.dminus14.app.feature.mypage

import com.dminus14.app.domain.model.InterviewReportListItem
import com.dminus14.app.domain.model.Portfolio
import com.dminus14.app.domain.model.PortfolioStatus
import com.dminus14.app.domain.model.UserProfile
import java.util.Locale

/**
 * 도메인 모델을 마이 페이지 UI 모델로 변환하는 규칙을 모은다.
 *
 * 값이 없으면 "내용 없음"으로 통일해 표시하고, 이메일은 표시 직전에만 마스킹해 원문을 로그·분석에
 * 남기지 않는다.
 */

private const val NO_CONTENT = "내용 없음"
private const val FALLBACK_REPORT_TITLE = "면접 리포트"
private const val KAKAO_PROVIDER = "KAKAO"
private const val EMAIL_MASK = "****"
private const val LOCAL_PART_LONG_THRESHOLD = 4
private const val LOCAL_PART_LONG_VISIBLE = 3
private const val LOCAL_PART_SHORT_THRESHOLD = 2
private const val LOCAL_PART_SHORT_VISIBLE = 1
private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = 1024.0 * 1024.0

internal fun UserProfile.toProfileUiModel(): MyPageProfileUiModel =
    MyPageProfileUiModel(
        name = name ?: NO_CONTENT,
        role = jobRoleLabel ?: jobRole ?: NO_CONTENT,
        experience = careerYears?.let { years -> "${years}년차" } ?: NO_CONTENT,
    )

internal fun UserProfile.toSocialAccountUiModel(): MyPageSocialAccountUiModel? =
    provider?.let { rawProvider ->
        MyPageSocialAccountUiModel(
            providerLabel = if (rawProvider == KAKAO_PROVIDER) "카카오" else rawProvider,
            displayAccount = maskEmail(email),
            isKakaoProvider = rawProvider == KAKAO_PROVIDER,
        )
    }

/** 로컬 파트만 마스킹하고 도메인 파트는 그대로 노출한다. 별표는 항상 4개로 원문 길이를 감춘다. */
@Suppress("ReturnCount")
internal fun maskEmail(email: String?): String {
    if (email.isNullOrBlank()) return NO_CONTENT
    val atIndex = email.indexOf('@')
    if (atIndex <= 0) return NO_CONTENT

    val localPart = email.substring(0, atIndex)
    val domainPart = email.substring(atIndex)
    val visibleLength =
        when {
            localPart.length >= LOCAL_PART_LONG_THRESHOLD -> LOCAL_PART_LONG_VISIBLE
            localPart.length >= LOCAL_PART_SHORT_THRESHOLD -> LOCAL_PART_SHORT_VISIBLE
            else -> 0
        }
    return localPart.take(visibleLength) + EMAIL_MASK + domainPart
}

internal fun Portfolio.toUiModel(): MyPagePortfolioUiModel =
    MyPagePortfolioUiModel(
        fileName = fileName,
        uploadedAt = formatUploadedAt(uploadedAt),
        fileSize = formatFileSize(fileSize),
    )

internal fun formatUploadedAt(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.substringBefore('T').replace('-', '.')
}

internal fun formatFileSize(bytes: Long): String =
    if (bytes < BYTES_PER_MB) {
        String.format(Locale.US, "%.1fKB", bytes / BYTES_PER_KB)
    } else {
        String.format(Locale.US, "%.1fMB", bytes / BYTES_PER_MB)
    }

/**
 * 서버 상태를 화면 상태로 변환한다.
 *
 * [previousState]가 이미 [MyPagePortfolioState.Completed]면(이번 화면에서 방금 업로드를 끝낸
 * 경우) `READY`를 다시 [MyPagePortfolioState.Completed]로 유지한다. 그 외의 `READY`는 항상
 * [MyPagePortfolioState.Uploaded]다.
 */
internal fun Portfolio.toPortfolioState(previousState: MyPagePortfolioState): MyPagePortfolioState =
    when (status) {
        PortfolioStatus.READY -> {
            if (previousState is MyPagePortfolioState.Completed) {
                MyPagePortfolioState.Completed(fileName)
            } else {
                MyPagePortfolioState.Uploaded(toUiModel())
            }
        }

        PortfolioStatus.PROCESSING -> {
            MyPagePortfolioState.Uploading(fileName = fileName, portfolioId = portfolioId)
        }

        PortfolioStatus.FAILED_FILE, PortfolioStatus.FAILED_SYSTEM -> {
            MyPagePortfolioState.Failed(fileName = fileName, portfolioId = portfolioId)
        }

        PortfolioStatus.UNKNOWN -> {
            MyPagePortfolioState.Empty
        }
    }

internal fun InterviewReportListItem.toReportUiModel(): MyPageReportUiModel {
    val status = MyPageReportStatus.valueOf(reportStatus.name)
    val jobRole = MyPageJobRole.fromRaw(jobType)
    val resolvedJobRoleLabel =
        jobTypeLabel?.takeIf { it.isNotBlank() }
            ?: jobRole?.label
            ?: jobType?.takeIf { it.isNotBlank() }
    val snapshotTitle = formatReportSnapshotTitle(resolvedJobRoleLabel, careerYears)
    val validFileName = portfolioFileName?.takeIf { it.isNotBlank() }
    val usesPortfolioFileNameTitle = portfolioDeleted || status == MyPageReportStatus.FAILED
    val headerTitle =
        if (usesPortfolioFileNameTitle) {
            validFileName ?: snapshotTitle
        } else {
            snapshotTitle
        }

    return MyPageReportUiModel(
        id = sessionId.toString(),
        title = headerTitle,
        jobRole = jobRole,
        jobRoleLabel = resolvedJobRoleLabel ?: NO_CONTENT,
        experienceYears = careerYears,
        createdAt = interviewedAt.orEmpty(),
        status = status,
        portfolioFileName = validFileName ?: NO_CONTENT,
        isPortfolioDeleted = portfolioDeleted,
        jobDescription = jdUrl ?: "JD 직접 입력",
        isFeedbackAvailable = feedbackAvailable,
    )
}

private fun formatReportSnapshotTitle(
    jobRoleLabel: String?,
    careerYears: Int?,
): String {
    val pieces =
        listOfNotNull(
            jobRoleLabel?.takeIf { it.isNotBlank() },
            careerYears?.let { years -> "${years}년차" },
        )
    return if (pieces.isEmpty()) FALLBACK_REPORT_TITLE else "${pieces.joinToString(" · ")} 면접"
}
