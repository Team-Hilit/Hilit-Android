package com.dminus14.app.feature.interview.interview

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dminus14.app.feature.interview.component.InterviewFinalizationFailureModal
import com.dminus14.designsystem.theme.HilitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InterviewScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `시작 안내 단계에서 시작 버튼을 표시한다`() {
        setContent(
            InterviewState(
                screenState = InterviewScreenState.START_GUIDE,
                isCameraPermissionGranted = true,
                isCameraReady = true,
                isMicrophoneReady = true,
                isServerReady = true,
                hasEnoughStorage = true,
            ),
        )

        composeRule.onNodeWithText("면접 시작하기").assertIsDisplayed()
    }

    @Test
    fun `답변 제출 단계에서 정리 문구를 표시한다`() {
        setContent(InterviewState(screenState = InterviewScreenState.ANSWER_SUBMITTING))

        composeRule.onNodeWithText("답변을 정리하고 있어요").assertIsDisplayed()
    }

    @Test
    fun `마무리 단계에서 업로드 보존 안내를 표시한다`() {
        setContent(InterviewState(screenState = InterviewScreenState.FINISHING))

        composeRule.onNodeWithText("24시간 안에", substring = true).assertIsDisplayed()
    }

    @Test
    fun `마무리 실패 Modal은 문구를 표시하고 각 버튼 Intent를 한 번만 전달한다`() {
        var exitCount = 0
        var retryCount = 0
        setFinalizationFailureModal(
            onExit = { exitCount++ },
            onRetry = { retryCount++ },
        )

        composeRule.onNodeWithText("면접 마무리를 완료하지 못했어요").assertIsDisplayed()
        composeRule.onNodeWithText("영상은 최대 24시간", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("홈으로 이동").performClick()
        composeRule.onNodeWithText("다시 시도").performClick()

        assertEquals(1, exitCount)
        assertEquals(1, retryCount)
    }

    @Test
    fun `마무리 실패 Modal은 뒤로 가기와 외부 터치로 닫히지 않는다`() {
        setFinalizationFailureModal()

        pressSystemBack()
        tapOutsideDialog()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("면접 마무리를 완료하지 못했어요").assertIsDisplayed()
    }

    private fun setContent(state: InterviewState) {
        composeRule.setContent {
            HilitTheme { InterviewContent(state = state, onIntent = {}) }
        }
    }

    private fun setFinalizationFailureModal(
        onExit: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            HilitTheme {
                InterviewFinalizationFailureModal(
                    onExitClick = onExit,
                    onRetryClick = onRetry,
                )
            }
        }
    }

    private fun pressSystemBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        check(
            instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(
                    downTime,
                    downTime,
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_BACK,
                    0,
                ),
                true,
            ),
        )
        SystemClock.sleep(50)
        check(
            instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(
                    downTime,
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_BACK,
                    0,
                ),
                true,
            ),
        )
    }

    private fun tapOutsideDialog() {
        val decorView = composeRule.activity.window.decorView
        val location = IntArray(2)
        composeRule.runOnUiThread { decorView.getLocationOnScreen(location) }
        tapScreen(
            Offset(
                x = location[0] + decorView.width * 0.05f,
                y = location[1] + decorView.height * 0.5f,
            ),
        )
    }

    private fun tapScreen(position: Offset) {
        val downTime = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downEvent =
            MotionEvent
                .obtain(
                    downTime,
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    position.x,
                    position.y,
                    0,
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val upEvent =
            MotionEvent
                .obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    position.x,
                    position.y,
                    0,
                ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            check(instrumentation.uiAutomation.injectInputEvent(downEvent, true))
            SystemClock.sleep(50)
            check(instrumentation.uiAutomation.injectInputEvent(upEvent, true))
        } finally {
            downEvent.recycle()
            upEvent.recycle()
        }
    }
}
