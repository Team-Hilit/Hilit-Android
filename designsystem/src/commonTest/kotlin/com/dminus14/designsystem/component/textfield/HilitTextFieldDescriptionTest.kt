package com.dminus14.designsystem.component.textfield

import com.dminus14.designsystem.component.subtext.HilitSubTextType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HilitTextFieldDescriptionTest {
    @Test
    fun `비동기 입력란은 상태 메시지가 없으면 안내를 표시한다`() {
        HilitAsyncTextFieldType.entries
            .filter {
                it != HilitAsyncTextFieldType.Processing
            }.forEach { type ->
                assertEquals(
                    "안내" to HilitSubTextType.Default,
                    resolveAsyncTextFieldSubText(type, "", "안내"),
                )
            }
    }

    @Test
    fun `비동기 입력란은 기존 표시 조건에 맞는 상태 메시지를 안내보다 우선한다`() {
        listOf(
            HilitAsyncTextFieldType.Ready to HilitSubTextType.Default,
            HilitAsyncTextFieldType.Complete to HilitSubTextType.Success,
            HilitAsyncTextFieldType.Error to HilitSubTextType.Error,
        ).forEach { (type, subTextType) ->
            assertEquals(
                "상태" to subTextType,
                resolveAsyncTextFieldSubText(type, "상태", "안내"),
            )
        }
        listOf(HilitAsyncTextFieldType.Focus, HilitAsyncTextFieldType.Edit).forEach { type ->
            assertEquals(
                "안내" to HilitSubTextType.Default,
                resolveAsyncTextFieldSubText(type, "상태", "안내"),
            )
        }
    }

    @Test
    fun `비동기 입력란은 오류 메시지가 해제되면 안내를 복원한다`() {
        assertEquals(
            "오류" to HilitSubTextType.Error,
            resolveAsyncTextFieldSubText(HilitAsyncTextFieldType.Error, "오류", "안내"),
        )
        assertEquals(
            "안내" to HilitSubTextType.Default,
            resolveAsyncTextFieldSubText(HilitAsyncTextFieldType.Edit, "", "안내"),
        )
    }

    @Test
    fun `비동기 입력란은 분석 중이면 상태 메시지와 안내를 숨긴다`() {
        assertNull(resolveAsyncTextFieldSubText(HilitAsyncTextFieldType.Processing, "상태", "안내"))
    }

    @Test
    fun `안내를 생략한 비동기 입력란은 기존 상태 메시지 표시 조건을 유지한다`() {
        HilitAsyncTextFieldType.entries.forEach { type ->
            val expected =
                when (type) {
                    HilitAsyncTextFieldType.Ready -> "상태" to HilitSubTextType.Default
                    HilitAsyncTextFieldType.Complete -> "상태" to HilitSubTextType.Success
                    HilitAsyncTextFieldType.Error -> "상태" to HilitSubTextType.Error
                    else -> null
                }
            assertEquals(expected, resolveAsyncTextFieldSubText(type, "상태"))
            assertNull(resolveAsyncTextFieldSubText(type, ""))
        }
    }

    @Test
    fun `장문 입력란은 빈 입력과 검증 없는 입력 모두 안내를 표시한다`() {
        listOf("", "입력한 내용").forEach { value ->
            assertEquals("안내" to HilitSubTextType.Default, jdFooter(value = value))
        }
        assertEquals("안내" to HilitSubTextType.Default, jdFooter(value = "", minLength = 10))
    }

    @Test
    fun `장문 입력란은 명시적 오류를 길이 검증과 안내보다 우선한다`() {
        listOf("", "짧음", "충분히 긴 입력 내용").forEach { value ->
            assertEquals(
                "명시적 오류" to HilitSubTextType.Error,
                jdFooter(value = value, minLength = 10, explicitErrorText = "명시적 오류"),
            )
        }
    }

    @Test
    fun `장문 입력란은 길이 오류와 성공 메시지를 안내보다 우선한다`() {
        assertEquals("길이 오류" to HilitSubTextType.Error, jdFooter(value = "짧음", minLength = 10))
        assertEquals(
            "성공" to HilitSubTextType.Success,
            jdFooter(value = "충분히 긴 입력 내용", minLength = 10),
        )
    }

    @Test
    fun `장문 입력란은 명시적 오류가 해제되면 안내나 길이 검증 메시지를 복원한다`() {
        assertEquals("안내" to HilitSubTextType.Default, jdFooter(explicitErrorText = ""))
        assertEquals("안내" to HilitSubTextType.Default, jdFooter(explicitErrorText = null))
        assertEquals("길이 오류" to HilitSubTextType.Error, jdFooter(value = "짧음", minLength = 10))
    }

    @Test
    fun `장문 입력란은 검증 문구가 비어 있으면 안내를 표시한다`() {
        listOf("짧음", "충분히 긴 입력 내용").forEach { value ->
            assertEquals(
                "안내" to HilitSubTextType.Default,
                resolveJDTextFieldSubText(value, 10, "", "", null, "안내"),
            )
        }
    }

    @Test
    fun `안내를 생략한 장문 입력란은 기존 메시지와 빈 하단 상태를 유지한다`() {
        assertNull(resolveJDTextFieldSubText("", 10, "오류", "성공", null))
        assertNull(resolveJDTextFieldSubText("입력", null, "오류", "성공", null))
        assertNull(resolveJDTextFieldSubText("입력", 10, "", "", null))
        assertEquals(
            "오류" to HilitSubTextType.Error,
            resolveJDTextFieldSubText("입력", 10, "오류", "성공", null),
        )
        assertEquals(
            "성공" to HilitSubTextType.Success,
            resolveJDTextFieldSubText("입력", 2, "오류", "성공", null),
        )
    }

    private fun jdFooter(
        value: String = "입력",
        minLength: Int? = null,
        explicitErrorText: String? = null,
    ): Pair<String, HilitSubTextType>? =
        resolveJDTextFieldSubText(value, minLength, "길이 오류", "성공", explicitErrorText, "안내")
}
