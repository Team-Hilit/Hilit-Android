@file:Suppress("ktlint:standard:filename")

package com.dminus14.app.feature.interviewreport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dminus14.app.feature.interviewreport.component.DetailReportSectionHeader
import com.dminus14.app.feature.interviewreport.component.GuestFeedbackSection
import com.dminus14.app.feature.interviewreport.component.HeadlineSection
import com.dminus14.app.feature.interviewreport.component.HighlightDetailBottomSheet
import com.dminus14.app.feature.interviewreport.component.QuestionTabRow
import com.dminus14.app.feature.interviewreport.component.ReportCard
import com.dminus14.app.feature.interviewreport.component.VideoCountdownCard
import com.dminus14.app.feature.interviewreport.model.ReportUiModel
import com.dminus14.designsystem.component.button.HilitButtonType
import com.dminus14.designsystem.component.button.HilitFixedBottomButton
import com.dminus14.designsystem.component.icon.HilitIcon
import com.dminus14.designsystem.component.icon.HilitIconAsset
import com.dminus14.designsystem.component.loading.HilitLoadingIndicator
import com.dminus14.designsystem.theme.HilitTheme

/**
 * 리포트 화면 순수 UI. State 만 받아 렌더하고 콜백으로 이벤트를 전달한다. (Figma Node: 443:6889)
 */
@Composable
@Suppress("LongMethod")
internal fun InterviewReportContent(
    state: InterviewReportState,
    onIntent: (InterviewReportIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HilitTheme.colors
    Box(
        modifier = modifier.fillMaxSize().background(colors.hilitBlack900),
    ) {
        when (val phase = state.phase) {
            InterviewReportState.Phase.Loading -> {
                // 폴링 중 화면을 벗어날 수 있도록 닫기 버튼은 계속 노출한다.
                Column(modifier = Modifier.fillMaxSize()) {
                    ReportTopBar(onClose = { onIntent(InterviewReportIntent.ClickClose) })
                    // weight(1f)만 주면 Column 안에서 폭은 콘텐츠(스피너)에 맞춰 줄어들고,
                    // Column 기본 정렬(Alignment.Start)에 따라 그 좁은 Box 자체가 왼쪽에
                    // 붙어버려 스피너가 화면 중앙이 아니라 왼쪽으로 치우쳐 보였다(#176).
                    // fillMaxWidth() 를 더해 Box 폭을 화면 전체로 넓혀야 안의 Alignment.Center
                    // 가 실제로 화면 가로 중앙을 기준으로 동작한다.
                    CenteredMessage(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        content = { HilitLoadingIndicator() },
                    )
                }
            }

            InterviewReportState.Phase.Failed -> {
                // 닫기(ReportTopBar)와 다시 시도(ClickRetry) 둘 다 실제로 탭할 수 있게 연결한다.
                // 이전에는 안내 문구만 있고 액션이 없어 막다른 화면이었다.
                Column(modifier = Modifier.fillMaxSize()) {
                    ReportTopBar(onClose = { onIntent(InterviewReportIntent.ClickClose) })
                    // Loading phase 와 같은 이유로 fillMaxWidth() 가 필요하다(#176).
                    CenteredMessage(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        content = {
                            Text(
                                text = "리포트를 불러오지 못했어요. 다시 시도해 주세요.",
                                style = HilitTheme.typography.sub7,
                                color = colors.gray200,
                                textAlign = TextAlign.Center,
                            )
                        },
                    )
                    HilitFixedBottomButton(
                        text = "다시 시도",
                        type = HilitButtonType.Light,
                        onClick = { onIntent(InterviewReportIntent.ClickRetry) },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            }

            is InterviewReportState.Phase.InsufficientAnalysis -> {
                ReadyReport(
                    report = phase.report,
                    state = state,
                    onIntent = onIntent,
                )
            }

            is InterviewReportState.Phase.Ready -> {
                ReadyReport(
                    report = phase.report,
                    state = state,
                    onIntent = onIntent,
                )
            }
        }

        val selected = state.selectedHighlight
        val report = state.phase.reportOrNull()
        if (selected != null && report != null) {
            val card = report.cards.getOrNull(selected.cardIndex)
            val highlight = card?.highlights?.getOrNull(selected.spanIndex)
            if (card != null && highlight != null) {
                HighlightDetailBottomSheet(
                    highlight = highlight,
                    transcript = card.transcript,
                    cardRedFlagNotices = card.cardRedFlagNotices,
                    showWatchSceneButton = true,
                    onDismiss = { onIntent(InterviewReportIntent.DismissHighlight) },
                    onWatchScene = {
                        highlight.startSec?.let {
                            onIntent(
                                InterviewReportIntent.ClickWatchScene(it),
                            )
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
private fun ReadyReport(
    report: ReportUiModel,
    state: InterviewReportState,
    onIntent: (InterviewReportIntent) -> Unit,
) {
    val scrollState = rememberScrollState()
    val refreshState = rememberPullToRefreshState()
    val thresholdPx =
        with(LocalDensity.current) { PullToRefreshDefaults.PositionalThreshold.toPx() }
    val indicatorSize = 60.dp
    val indicatorSizePx = with(LocalDensity.current) { indicatorSize.toPx() }
    Column(modifier = Modifier.fillMaxSize()) {
        ReportTopBar(onClose = { onIntent(InterviewReportIntent.ClickClose) })
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onIntent(InterviewReportIntent.RefreshReport) },
            state = refreshState,
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            indicator = {
                if (state.isRefreshing || refreshState.distanceFraction > 0f) {
                    HilitLoadingIndicator(
                        size = indicatorSize,
                        modifier =
                            Modifier.align(Alignment.TopCenter).graphicsLayer {
                                val fraction = refreshState.distanceFraction
                                val scale = fraction.coerceIn(0f, 1f)
                                scaleX = scale
                                scaleY = scale
                                translationY = (thresholdPx * fraction - indicatorSizePx) / 2f
                            },
                        contentDescription = "리포트 새로고침 중",
                    )
                }
            },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = thresholdPx * refreshState.distanceFraction
                        }.verticalScroll(scrollState)
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 40.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                HeadlineSection(headline = report.headline)
                Spacer(Modifier.height(24.dp))
                VideoCountdownCard(
                    video = report.video,
                    expirySeconds = state.videoExpirySeconds,
                    onClick = { onIntent(InterviewReportIntent.ClickWatchVideo) },
                )
                if (report.cards.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    SectionDivider()
                    Spacer(Modifier.height(24.dp))
                    val safeIndex = state.selectedCardIndex.coerceIn(0, report.cards.lastIndex)
                    DetailReportSectionHeader(
                        resolutionNotice = report.cards[safeIndex].resolutionNotice,
                    )
                    Spacer(Modifier.height(10.dp))
                    QuestionTabRow(
                        cards = report.cards,
                        selectedIndex = safeIndex,
                        onSelect = { onIntent(InterviewReportIntent.SelectCard(it)) },
                    )
                    Spacer(Modifier.height(24.dp))
                    ReportCard(
                        card = report.cards[safeIndex],
                        cardIndex = safeIndex,
                        onHighlightClick = { cardIndex, spanIndex ->
                            onIntent(
                                InterviewReportIntent.ClickHighlight(cardIndex, spanIndex),
                            )
                        },
                    )
                }
                report.guestFeedback?.let { guestFeedback ->
                    Spacer(Modifier.height(36.dp))
                    GuestFeedbackSection(
                        model = guestFeedback,
                        selectedGuestIndex = state.selectedGuestFeedbackIndex,
                        onSelectGuest = {
                            onIntent(InterviewReportIntent.SelectGuestFeedback(it))
                        },
                        onClickInvite = {
                            onIntent(InterviewReportIntent.ClickGuestFeedbackInvite)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportTopBar(onClose: () -> Unit) {
    val colors = HilitTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HilitIcon(
            asset = HilitIconAsset.Cancel,
            contentDescription = "닫기",
            tint = colors.hilitWhite,
            modifier = Modifier.clickable(onClick = onClose),
        )
    }
}

@Composable
private fun SectionDivider() {
    val colors = HilitTheme.colors
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.gray800))
}

/** Ready / InsufficientAnalysis 두 Phase 가 담은 리포트를 꺼낸다. 그 외에는 null. */
private fun InterviewReportState.Phase.reportOrNull(): ReportUiModel? =
    when (this) {
        is InterviewReportState.Phase.Ready -> report
        is InterviewReportState.Phase.InsufficientAnalysis -> report
        else -> null
    }

@Composable
private fun CenteredMessage(
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
