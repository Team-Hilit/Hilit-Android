@file:Suppress("TooManyFunctions")

package com.dminus14.app.feature.mypage.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dminus14.app.feature.mypage.MyPageJobRole
import com.dminus14.app.feature.mypage.MyPageReportStatus
import com.dminus14.app.feature.mypage.MyPageReportUiModel
import com.dminus14.designsystem.component.emptystate.HilitEmptyState
import com.dminus14.designsystem.component.icon.HilitIcon
import com.dminus14.designsystem.component.icon.HilitIconAsset
import com.dminus14.designsystem.component.tag.HilitTag
import com.dminus14.designsystem.component.tag.TagColorType
import com.dminus14.designsystem.component.tag.TagType
import com.dminus14.designsystem.theme.HilitTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val ReportCreatedAtFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd. HH:mm")

@Composable
@Suppress("LongParameterList")
internal fun MyPageReportSection(
    reports: List<MyPageReportUiModel>,
    expandedReportIds: Set<String>,
    onReportToggleClick: (String) -> Unit,
    onReportViewClick: (String) -> Unit,
    onGuestFeedbackClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "내 면접 리포트",
            style = HilitTheme.typography.body6,
            color = HilitTheme.colors.gray500,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (reports.isEmpty()) {
            HilitEmptyState(
                text = "면접을 시작하고 리포트를 받아보세요!",
            )
        } else {
            reports.forEach { report ->
                ReportCard(
                    report = report,
                    expanded = report.id in expandedReportIds,
                    onToggleClick = { onReportToggleClick(report.id) },
                    onReportViewClick = { onReportViewClick(report.id) },
                    onGuestFeedbackClick = { onGuestFeedbackClick(report.id) },
                )
            }
        }
    }
}

/** [MyPageReportStatus.GENERATING]·[MyPageReportStatus.FAILED]만 상단 배지를 노출한다. */
private fun MyPageReportStatus.badgeText(): String? =
    when (this) {
        MyPageReportStatus.GENERATING -> "생성 중"

        MyPageReportStatus.FAILED -> "생성 실패"

        MyPageReportStatus.READY,
        MyPageReportStatus.INSUFFICIENT_ANALYSIS,
        MyPageReportStatus.UNKNOWN,
        -> null
    }

private fun MyPageReportStatus.badgeColor(): TagColorType =
    if (this == MyPageReportStatus.FAILED) TagColorType.Red else TagColorType.Gray

/** [MyPageReportStatus.READY]·[MyPageReportStatus.INSUFFICIENT_ANALYSIS]만 하단 액션을 노출한다. */
private fun MyPageReportStatus.showsActions(): Boolean =
    this == MyPageReportStatus.READY || this == MyPageReportStatus.INSUFFICIENT_ANALYSIS

@Composable
@Suppress("LongMethod", "MagicNumber")
private fun ReportCard(
    report: MyPageReportUiModel,
    expanded: Boolean,
    onToggleClick: () -> Unit,
    onReportViewClick: () -> Unit,
    onGuestFeedbackClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    HilitTheme.colors.gray100,
                ).border(1.dp, HilitTheme.colors.gray100),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(color = HilitTheme.colors.hilitWhite)
                    .clickable(role = Role.Button, onClick = onToggleClick)
                    .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        report.title,
                        style = HilitTheme.typography.body2,
                        color = HilitTheme.colors.gray800,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ReportMetaRow(
                        createdAt = report.createdAt.toReportCreatedAtText(),
                        isPortfolioDeleted = report.isPortfolioDeleted,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(15.dp),
                ) {
                    report.status.badgeText()?.let { badgeText ->
                        HilitTag(
                            colorType = report.status.badgeColor(),
                            tagType = TagType.Small,
                            text = badgeText,
                        )
                    }
                    HilitIcon(
                        asset = if (expanded) HilitIconAsset.Up else HilitIconAsset.Down,
                        contentDescription = if (expanded) "접기" else "펼치기",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (expanded) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            HilitTheme.colors.gray50,
                        ).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportMetadataRow(
                        label = "직군 · 연차",
                        value = report.jobRoleAndExperience,
                    )
                    ReportMetadataRow(
                        label = "포트폴리오",
                        value = report.portfolioFileName,
                    )
                    ReportMetadataRow(
                        label = "JD",
                        value = report.jobDescription,
                    )
                }
                if (report.status == MyPageReportStatus.FAILED) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(color = HilitTheme.colors.error200)
                                .padding(horizontal = 4.dp),
                    ) {
                        Text(
                            text = "리포트 생성에 실패했어요 · 횟수는 차감되지 않았어요",
                            style = HilitTheme.typography.body6,
                            color = HilitTheme.colors.error500,
                        )
                    }
                }
                if (report.status.showsActions()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportAction(
                            text = "리포트 보기",
                            isPrimary = false,
                            onClick = onReportViewClick,
                            modifier = Modifier.weight(1f),
                        )
                        if (report.isFeedbackAvailable) {
                            ReportAction(
                                text = "지인 피드백 받기",
                                isPrimary = true,
                                onClick = onGuestFeedbackClick,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportMetaRow(
    createdAt: String,
    isPortfolioDeleted: Boolean,
) {
    if (createdAt.isEmpty() && !isPortfolioDeleted) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (createdAt.isNotEmpty()) {
            Text(
                text = createdAt,
                style = HilitTheme.typography.body10,
                color = HilitTheme.colors.gray400,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (isPortfolioDeleted) {
            HilitTag(
                colorType = TagColorType.Gray,
                tagType = TagType.Small,
                text = "삭제된 포트폴리오",
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

private fun String.toReportCreatedAtText(): String =
    runCatching {
        LocalDateTime.parse(this).format(ReportCreatedAtFormatter)
    }.getOrDefault("")

@Composable
private fun ReportMetadataRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(70.dp),
            style = HilitTheme.typography.body7,
            color = HilitTheme.colors.gray400,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = HilitTheme.typography.body6,
            color = HilitTheme.colors.gray700,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReportAction(
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier =
            modifier
                .background(
                    if (isPrimary) {
                        HilitTheme.colors.hilitBlack800
                    } else {
                        HilitTheme.colors.hilitWhite
                    },
                ).clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 8.dp),
        style = HilitTheme.typography.body5,
        color =
            if (isPrimary) {
                HilitTheme.colors.hilitWhite
            } else {
                HilitTheme.colors.gray700
            },
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private fun sampleReport(
    status: MyPageReportStatus,
    isFeedbackAvailable: Boolean = true,
    isPortfolioDeleted: Boolean = false,
    portfolioFileName: String = "sample_portfolio.pdf",
): MyPageReportUiModel =
    MyPageReportUiModel(
        id = "sample-$status-$isPortfolioDeleted",
        title =
            if (isPortfolioDeleted || status == MyPageReportStatus.FAILED) {
                portfolioFileName
            } else {
                "${MyPageJobRole.ANDROID.label} · 3년차 면접"
            },
        jobRole = MyPageJobRole.ANDROID,
        jobRoleLabel = MyPageJobRole.ANDROID.label,
        experienceYears = 3,
        createdAt = "2026-08-04T14:20:00.000000",
        status = status,
        portfolioFileName = portfolioFileName,
        isPortfolioDeleted = isPortfolioDeleted,
        jobDescription = "careers.example.com/jobs/1024",
        isFeedbackAvailable = isFeedbackAvailable,
    )

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun MyPageReportSectionPreview() {
    val report = sampleReport(MyPageReportStatus.READY)
    HilitTheme {
        MyPageReportSection(
            reports = listOf(report),
            expandedReportIds = setOf(report.id),
            onReportToggleClick = {},
            onReportViewClick = {},
            onGuestFeedbackClick = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun MyPageReportSectionEmptyPreview() {
    HilitTheme {
        MyPageReportSection(
            reports = emptyList(),
            expandedReportIds = emptySet(),
            onReportToggleClick = {},
            onReportViewClick = {},
            onGuestFeedbackClick = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun ReportCardGeneratingPreview() {
    HilitTheme {
        ReportCard(
            report = sampleReport(MyPageReportStatus.GENERATING),
            expanded = true,
            onToggleClick = {},
            onReportViewClick = {},
            onGuestFeedbackClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun ReportCardReadyFeedbackAvailablePreview() {
    HilitTheme {
        ReportCard(
            report = sampleReport(MyPageReportStatus.READY, isFeedbackAvailable = true),
            expanded = true,
            onToggleClick = {},
            onReportViewClick = {},
            onGuestFeedbackClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun ReportCardReadyFeedbackUnavailablePreview() {
    HilitTheme {
        ReportCard(
            report = sampleReport(MyPageReportStatus.READY, isFeedbackAvailable = false),
            expanded = true,
            onToggleClick = {},
            onReportViewClick = {},
            onGuestFeedbackClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun ReportCardInsufficientAnalysisPreview() {
    HilitTheme {
        ReportCard(
            report = sampleReport(MyPageReportStatus.INSUFFICIENT_ANALYSIS),
            expanded = true,
            onToggleClick = {},
            onReportViewClick = {},
            onGuestFeedbackClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun ReportCardFailedPreview() {
    HilitTheme {
        ReportCard(
            report = sampleReport(MyPageReportStatus.FAILED),
            expanded = true,
            onToggleClick = {},
            onReportViewClick = {},
            onGuestFeedbackClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun ReportCardPortfolioDeletedPreview() {
    HilitTheme {
        ReportCard(
            report = sampleReport(MyPageReportStatus.READY, isPortfolioDeleted = true),
            expanded = true,
            onToggleClick = {},
            onReportViewClick = {},
            onGuestFeedbackClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun ReportCardFailedAndPortfolioDeletedPreview() {
    HilitTheme {
        ReportCard(
            report =
                sampleReport(
                    status = MyPageReportStatus.FAILED,
                    isFeedbackAvailable = false,
                    isPortfolioDeleted = true,
                ),
            expanded = false,
            onToggleClick = {},
            onReportViewClick = {},
            onGuestFeedbackClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun ReportCardUnknownPreview() {
    HilitTheme {
        ReportCard(
            report = sampleReport(MyPageReportStatus.UNKNOWN),
            expanded = true,
            onToggleClick = {},
            onReportViewClick = {},
            onGuestFeedbackClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 375)
@Composable
private fun ReportActionPreview() {
    HilitTheme {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReportAction(
                text = "리포트 보기",
                isPrimary = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            ReportAction(
                text = "지인 피드백 받기",
                isPrimary = true,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
    }
}
