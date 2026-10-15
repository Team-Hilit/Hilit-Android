@file:Suppress("TooManyFunctions", "LongMethod", "CyclomaticComplexMethod")

package com.dminus14.app.feature.interviewreport.player

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dminus14.app.feature.interviewreport.component.HighlightDetailBottomSheet
import com.dminus14.app.feature.interviewreport.model.HighlightUiTone
import com.dminus14.app.feature.interviewreport.model.PlayerContentUiModel
import com.dminus14.app.feature.interviewreport.model.PlayerHighlightRefUiModel
import com.dminus14.app.feature.interviewreport.model.PlayerScriptLineUiModel
import com.dminus14.app.feature.interviewreport.model.PlayerSegmentUiModel
import com.dminus14.designsystem.component.icon.HilitIcon
import com.dminus14.designsystem.component.icon.HilitIconAsset
import com.dminus14.designsystem.component.loading.HilitLoadingIndicator
import com.dminus14.designsystem.theme.HilitColors
import com.dminus14.designsystem.theme.HilitTheme
import kotlinx.coroutines.delay

private const val POSITION_POLL_INTERVAL_MS = 250L
private const val MS_PER_SECOND = 1000f

/** 대본 오버레이의 최대 높이 (Figma 443:7941 기준, 화면을 다 덮지 않도록 상한을 둔다). */
private val TRANSCRIPT_OVERLAY_MAX_HEIGHT = 420.dp

/** 대본 오버레이 하단과 SegmentedProgressBar 사이 간격 (Figma 443:7941). */
private val TRANSCRIPT_OVERLAY_PROGRESS_BAR_GAP = 44.dp

/** 재생 컨트롤이 떠 있을 때 영상 위에 깔리는 dim 알파 (Figma 443:7852: hilit opacity/dark/65%). */
private const val VIDEO_CONTROL_DIM_ALPHA = 0.65f

/**
 * 영상 상/하단 가독성 스크림 높이. FullScreen 적용으로 영상이 상태바·네비게이션바 뒤까지
 * 채워지면서, 그 위에 얹히는 닫기 버튼·진행바·토글 버튼이 원본 영상과 바로 겹쳐 대비가 부족해질
 * 수 있어 상/하단에 그라디언트 스크림을 상시로 깐다(컨트롤 노출 여부와 무관).
 */
private val VIDEO_EDGE_SCRIM_HEIGHT = 140.dp

/**
 * 영상 플레이어 화면 (Figma Node: 443:7804 / 443:7877 / 443:7902 / 443:7972).
 *
 * ExoPlayer 로 합성 영상을 재생하고, 카드(질문)별 구간으로 나눈 세그먼트 진행바와 대본 오버레이를
 * 제공한다. 대본 오버레이는 최상위 script 타임라인으로 현재 발화 라인을 강조한다.
 *
 * - 중앙 재생/일시정지 + 이전/다음 섹션(세그먼트) 이동 컨트롤 (443:7877).
 * - 대본 오버레이는 화면 전체가 아니라 하단 일부만 덮고, 진행바/토글 버튼은 오버레이 위에 그려져
 *   계속 탭할 수 있다 (443:7902).
 * - 대본에서 하이라이트(관련 답변) 구간을 탭하면 [HighlightDetailBottomSheet] 를 그대로 재사용해
 *   띄우고 영상은 정지한다. 시트를 닫으면 다시 재생한다 (443:7972).
 */
@Composable
fun InterviewReportPlayerScreen(
    sessionId: Long,
    startSec: Float?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InterviewReportPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) {
        viewModel.onIntent(InterviewReportPlayerIntent.Load(sessionId))
    }
    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                InterviewReportPlayerEffect.NavigateBack -> onNavigateBack()
            }
        }
    }

    InterviewReportPlayerContent(
        state = state,
        startSec = startSec,
        onIntent = viewModel::onIntent,
        modifier = modifier,
    )
}

@Composable
private fun InterviewReportPlayerContent(
    state: InterviewReportPlayerState,
    startSec: Float?,
    onIntent: (InterviewReportPlayerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HilitTheme.colors
    Box(modifier = modifier.fillMaxSize().background(colors.hilitBlack900)) {
        when (val phase = state.phase) {
            InterviewReportPlayerState.Phase.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    HilitLoadingIndicator()
                }
            }

            InterviewReportPlayerState.Phase.Failed -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable { onIntent(InterviewReportPlayerIntent.ClickClose) }
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "영상을 불러오지 못했어요. 화면을 눌러 돌아가요.",
                        style = HilitTheme.typography.body7,
                        color = colors.gray300,
                    )
                }
            }

            is InterviewReportPlayerState.Phase.Ready -> {
                PlayerReady(
                    content = phase.content,
                    startSec = startSec,
                    transcriptVisible = state.transcriptVisible,
                    onIntent = onIntent,
                )
            }
        }
    }
}

@Composable
private fun PlayerReady(
    content: PlayerContentUiModel,
    startSec: Float?,
    transcriptVisible: Boolean,
    onIntent: (InterviewReportPlayerIntent) -> Unit,
) {
    val colors = HilitTheme.colors
    val context = LocalContext.current
    val url = content.videoUrl
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        startSec?.let { player.seekTo((it * MS_PER_SECOND).toLong()) }
        player.playWhenReady = true
    }
    // ExoPlayer 는 재생 오류(만료된 URL, 403, 네트워크 끊김 등)가 나도 onPlayerError 만 던지고
    // Compose 상태는 그대로라, 리스너를 안 달면 화면은 계속 Ready 인데 검은 화면만 남아 사용자가
    // 원인을 알 수 없다. 리포트 화면의 영상 만료 안내와 같은 톤으로 오류 문구를 노출한다.
    var playbackFailed by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackFailed = true
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // 화면/앱이 백그라운드로 가도(GuestFeedbackVideoPlayer 와 같은 패턴) 영상이 계속 재생되지
    // 않도록 멈춘다. 다시 돌아왔을 때 자동 재생하지 않고 사용자가 직접 재생을 누르게 둔다.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val lifecycleObserver =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) player.pause()
            }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose { lifecycleOwner.lifecycle.removeObserver(lifecycleObserver) }
    }

    // positionMs 는 State 객체(positionMsState) 그대로 하위 컴포저블에 넘기고, 실제 .value 읽기는
    // 그 컴포저블 안에서 by 로 한다. 여기 PlayerReady 본문에서 직접 읽으면(구 코드처럼 매개변수로
    // 값 자체를 넘기면) 읽는 시점이 이 함수 스코프가 되어, 250ms 마다 SurfaceView 를 포함한
    // PlayerReady 전체가 리컴포즈된다. 리컴포즈 스코프를 실제 소비처(대본 오버레이·진행바)로
    // 좁혀 영상 표면·컨트롤 등 나머지 트리는 갱신되지 않게 한다.
    val positionMsState = remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            positionMsState.longValue = player.currentPosition
            isPlaying = player.isPlaying
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    var selectedHighlightRef by remember {
        mutableStateOf<PlayerHighlightRefUiModel?>(null)
    }
    // 하이라이트 시트를 열기 전 재생 중이었는지 기억해뒀다가, 닫을 때 그때만 다시 재생한다.
    // 무조건 player.play() 하면 사용자가 직접 일시정지해둔 뒤 하이라이트를 탭한 경우에도
    // 영상이 제멋대로 다시 재생된다.
    var resumePlaybackOnHighlightDismiss by remember { mutableStateOf(false) }

    // 재생/일시정지·스킵 컨트롤은 기본적으로 숨겨져 있다가 영상을 탭하면 나오고, 다시 탭하면
    // 사라진다(기획: 항상 노출 아님). 컨트롤이 떠 있는 동안은 대본 오버레이를 자동으로 숨기고,
    // 컨트롤이 사라지면 transcriptVisible 값 자체는 건드리지 않았으므로 그대로 복원된다.
    var controlsVisible by remember { mutableStateOf(false) }
    val transcriptOverlayVisible = transcriptVisible && !controlsVisible

    Box(Modifier.fillMaxSize()) {
        if (playbackFailed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { onIntent(InterviewReportPlayerIntent.ClickClose) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "영상을 재생하지 못했어요. 화면을 눌러 돌아가요.",
                    style = HilitTheme.typography.body7,
                    color = colors.gray300,
                )
            }
        } else if (!url.isNullOrBlank()) {
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).also(player::setVideoSurfaceView) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "영상이 만료되어 재생할 수 없어요.",
                    style = HilitTheme.typography.body7,
                    color = colors.gray300,
                )
            }
        }

        // 영상 탭 감지 전용 레이어. 상단바·컨트롤·하단바처럼 자기 clickable 을 가진 요소가
        // 위에 그려지므로, 그 요소들과 겹치지 않는 자리를 탭했을 때만 이 레이어가 반응한다.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { controlsVisible = !controlsVisible },
        )

        // FullScreen 으로 영상이 상태바·네비게이션바 뒤까지 깔리면서, 그 위에 얹히는 닫기
        // 버튼(상단)·진행바/토글 버튼(하단)이 원본 영상과 바로 겹쳐 가독성이 떨어질 수 있다.
        // 컨트롤 노출 여부와 무관하게 상/하단에 항상 그라디언트 스크림을 깐다.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(VIDEO_EDGE_SCRIM_HEIGHT)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(colors.hilitBlack900, Color.Transparent),
                        ),
                    ),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(VIDEO_EDGE_SCRIM_HEIGHT)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, colors.hilitBlack900),
                        ),
                    ),
        )

        if (controlsVisible) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = VIDEO_CONTROL_DIM_ALPHA)),
            )
        }

        HilitIcon(
            asset = HilitIconAsset.Cancel,
            contentDescription = "닫기",
            tint = colors.hilitWhite,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(20.dp)
                    .clickable { onIntent(InterviewReportPlayerIntent.ClickClose) },
        )

        if (controlsVisible) {
            PlayerControls(
                isPlaying = isPlaying,
                onTogglePlay = {
                    if (player.isPlaying) player.pause() else player.play()
                },
                onSkipPrevious = {
                    val index = currentSegmentIndex(content.segments, positionMsState.longValue)
                    val target =
                        content.segments.getOrNull(index - 1) ?: content.segments.getOrNull(index)
                    target?.let { player.seekTo(it.startMs) }
                },
                onSkipNext = {
                    val index = currentSegmentIndex(content.segments, positionMsState.longValue)
                    content.segments.getOrNull(index + 1)?.let { player.seekTo(it.startMs) }
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 대본 오버레이는 SegmentedProgressBar 를 덮지 않도록, 두 영역을 겹치지 않는 하나의
        // 세로 스택으로 배치한다(둘 다 Alignment.BottomStart 로 서로 겹쳐 그리던 방식 대신).
        // 위쪽 Spacer(weight=1f) 가 남는 공간을 전부 먹어서 이 Column 자체는 화면 아래에 붙는다.
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.weight(1f))
            if (transcriptOverlayVisible) {
                TranscriptOverlay(
                    scriptLines = content.scriptLines,
                    positionMsState = positionMsState,
                    onHighlightClick = { ref ->
                        resumePlaybackOnHighlightDismiss = player.isPlaying
                        selectedHighlightRef = ref
                        player.pause()
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(TRANSCRIPT_OVERLAY_MAX_HEIGHT),
                )
                Spacer(modifier = Modifier.height(TRANSCRIPT_OVERLAY_PROGRESS_BAR_GAP))
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (content.segments.isNotEmpty()) {
                    SegmentedProgressBar(
                        segments = content.segments,
                        positionMsState = positionMsState,
                        onSeek = { player.seekTo(it) },
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TranscriptToggleButton(
                        visible = transcriptVisible,
                        onClick = { onIntent(InterviewReportPlayerIntent.ToggleTranscript) },
                    )
                }
            }
        }
    }

    selectedHighlightRef?.let { ref ->
        HighlightDetailBottomSheet(
            highlight = ref.highlight,
            transcript = ref.cardTranscript,
            cardRedFlagNotices = ref.cardRedFlagNotices,
            showWatchSceneButton = false,
            onDismiss = {
                selectedHighlightRef = null
                if (resumePlaybackOnHighlightDismiss) player.play()
            },
            onWatchScene = {},
        )
    }
}

/** [positionMs] 가 속한 세그먼트 index. 아직 첫 세그먼트 이전이면 0. */
private fun currentSegmentIndex(
    segments: List<PlayerSegmentUiModel>,
    positionMs: Long,
): Int = segments.indexOfLast { positionMs >= it.startMs }.coerceAtLeast(0)

@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HilitTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clickable(onClick = onSkipPrevious),
            contentAlignment = Alignment.Center,
        ) {
            HilitIcon(
                asset = HilitIconAsset.SkipLeft,
                contentDescription = "이전 섹션",
                tint = colors.hilitWhite,
                modifier = Modifier.size(34.dp),
            )
        }
        Box(
            modifier =
                Modifier
                    .background(colors.hilitGreen500)
                    .clickable(onClick = onTogglePlay)
                    .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            HilitIcon(
                asset = if (isPlaying) HilitIconAsset.Pause else HilitIconAsset.Play,
                contentDescription = if (isPlaying) "일시정지" else "재생",
                tint = colors.hilitGreen800,
                modifier = Modifier.size(34.dp),
            )
        }
        Box(
            modifier = Modifier.size(44.dp).clickable(onClick = onSkipNext),
            contentAlignment = Alignment.Center,
        ) {
            HilitIcon(
                asset = HilitIconAsset.SkipRight,
                contentDescription = "다음 섹션",
                tint = colors.hilitWhite,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun SegmentedProgressBar(
    segments: List<PlayerSegmentUiModel>,
    positionMsState: State<Long>,
    onSeek: (Long) -> Unit,
) {
    val colors = HilitTheme.colors
    val positionMs by positionMsState
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        segments.forEach { segment ->
            val span = (segment.endMs - segment.startMs).coerceAtLeast(1L)
            val fraction =
                ((positionMs - segment.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier =
                    Modifier
                        .weight(span.toFloat())
                        .height(6.dp)
                        .background(colors.gray700)
                        .clickable { onSeek(segment.startMs) },
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(colors.hilitGreen500),
                )
            }
        }
    }
}

@Composable
private fun TranscriptToggleButton(
    visible: Boolean,
    onClick: () -> Unit,
) {
    val colors = HilitTheme.colors
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .background(colors.gray800)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HilitIcon(
            asset = if (visible) HilitIconAsset.Cancel else HilitIconAsset.Script,
            contentDescription = if (visible) "대본 닫기" else "대본 보기",
            tint = colors.hilitWhite,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TranscriptOverlay(
    scriptLines: List<PlayerScriptLineUiModel>,
    positionMsState: State<Long>,
    onHighlightClick: (PlayerHighlightRefUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HilitTheme.colors
    val positionMs by positionMsState
    val listState = rememberLazyListState()
    val currentIndex = currentScriptLineIndex(scriptLines, positionMs)

    // positionMs 는 250ms 마다 갱신되지만(POSITION_POLL_INTERVAL_MS), currentIndex(재생 중인
    // 대본 줄)를 key 로 둬서 실제로 다음 줄로 넘어갈 때만 스크롤을 새로 트리거한다. 매 tick 마다
    // key 가 같으면 LaunchedEffect 가 재시작하지 않아 애니메이션이 끊기지 않는다.
    LaunchedEffect(currentIndex) {
        if (scriptLines.isNotEmpty()) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // key 를 line.startMs 로 두면 STT 결과 특성상 서로 다른 두 줄이 같은 시작 시각(ms)을
            // 가질 때 Compose 가 "Key ... was already used" 로 크래시한다. scriptLines 는 재생
            // 중 순서가 바뀌지 않으므로 index 를 key 로 써도 안전하고 항상 유일하다.
            itemsIndexed(scriptLines, key = { index, _ -> index }) { index, line ->
                val current = index == currentIndex
                val highlightRef = line.highlightRef
                // 하이라이트 구간은 Figma 443:7907처럼 줄바꿈된 실제 라인 폭에 딱 맞는 gray900
                // 배경 박스가 깔린다. Modifier.background 는 텍스트 블록 전체를 감싼 사각형 하나만
                // 그리므로, AnnotatedString + SpanStyle(background=...) 로 텍스트에 직접 입혀서
                // 줄바꿈 라인마다 자동으로 박스가 붙도록 한다.
                val text =
                    if (highlightRef != null) {
                        buildAnnotatedString {
                            withStyle(SpanStyle(background = colors.gray900)) {
                                append(line.text)
                            }
                        }
                    } else {
                        AnnotatedString(line.text)
                    }
                Text(
                    text = text,
                    style = HilitTheme.typography.body3,
                    color = line.toColor(colors, current),
                    modifier =
                        if (highlightRef != null) {
                            Modifier.clickable { onHighlightClick(highlightRef) }
                        } else {
                            Modifier
                        },
                )
            }
        }
    }
}

/**
 * [positionMs] 에 발화 중인 대본 줄 index. 정확히 그 시각을 포함하는 줄이 있으면 그 줄을 쓰고,
 * 발화 사이 공백처럼 걸치는 줄이 없으면 직전에 시작한 줄을 유지해 스크롤이 다음 줄이 시작하기
 * 전까지 튀지 않게 한다. 대본이 아직 시작 전이면 첫 줄(0)로 고정한다.
 */
private fun currentScriptLineIndex(
    scriptLines: List<PlayerScriptLineUiModel>,
    positionMs: Long,
): Int {
    val exactIndex = scriptLines.indexOfFirst { positionMs in it.startMs until it.endMs }
    if (exactIndex >= 0) return exactIndex
    return scriptLines.indexOfLast { positionMs >= it.startMs }.coerceAtLeast(0)
}

/**
 * 하이라이트 구간이면 톤 색(잘함=positive500, 개선=error400)을 항상 유지해 눈에 띄게 하고,
 * 그 외에는 기존처럼 현재 발화 중인 라인만 흰색, 나머지는 회색으로 낮춘다.
 */
private fun PlayerScriptLineUiModel.toColor(
    colors: HilitColors,
    current: Boolean,
): Color {
    val ref = highlightRef
    if (ref != null) {
        return when (ref.highlight.tone) {
            HighlightUiTone.POSITIVE -> colors.positive500
            HighlightUiTone.NEGATIVE -> colors.error400
            HighlightUiTone.NEUTRAL -> if (current) colors.hilitWhite else colors.gray500
        }
    }
    return if (current) colors.hilitWhite else colors.gray500
}

// Ready 프레임은 ExoPlayer 를 생성하므로 Preview 불가 → 로딩/실패 상태만 미리본다.
@Preview(name = "플레이어 - 로딩")
@Composable
private fun InterviewReportPlayerLoadingPreview() {
    HilitTheme {
        InterviewReportPlayerContent(
            state = InterviewReportPlayerState(phase = InterviewReportPlayerState.Phase.Loading),
            startSec = null,
            onIntent = {},
        )
    }
}

@Preview(name = "플레이어 - 실패")
@Composable
private fun InterviewReportPlayerFailedPreview() {
    HilitTheme {
        InterviewReportPlayerContent(
            state = InterviewReportPlayerState(phase = InterviewReportPlayerState.Phase.Failed),
            startSec = null,
            onIntent = {},
        )
    }
}
