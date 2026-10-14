package stories.components.designsystem.hilitasynctextfield

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dminus14.designsystem.component.textfield.HilitAsyncTextField
import com.dminus14.designsystem.component.textfield.HilitAsyncTextFieldType
import type.Story
import type.StoryGroup

private val PreviewSurfaceColor = Color(0xFF4A4B50)

internal val HilitAsyncTextFieldStories =
    StoryGroup(
        path = "Components/HilitAsyncTextField",
        description = "범용 비동기 처리 텍스트 필드. Ready/Focus/Edit/Processing/Complete/Error 상태.",
        stories =
            listOf(
                Story(
                    id = "default",
                    title = "기본",
                    description = "Controls로 안내(descriptionText), 상태 메시지(subText), 상태(type)를 조절한다.",
                ) {
                    HilitAsyncTextFieldCatalogAdapterControls(
                        initialArgs =
                            HilitAsyncTextFieldCatalogAdapterArgs(
                                value = "",
                                placeholder = "텍스트를 입력해주세요",
                                processingText = "처리 중",
                                subText = "",
                                descriptionText = "링크 입력을 원하지 않으면 넘어가도 괜찮아요.",
                                type = HilitAsyncTextFieldType.Ready,
                            ),
                    )
                },
                Story(
                    id = "all-types",
                    title = "전체 상태",
                    description = "안내보다 상태 메시지가 우선하며 Processing에서는 안내가 숨겨진다.",
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(PreviewSurfaceColor)
                                .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        HilitAsyncTextFieldType.entries.forEach { type ->
                            HilitAsyncTextField(
                                value =
                                    when (type) {
                                        HilitAsyncTextFieldType.Edit,
                                        HilitAsyncTextFieldType.Processing,
                                        HilitAsyncTextFieldType.Complete,
                                        HilitAsyncTextFieldType.Error,
                                        -> "https://company.com/jobs/123"

                                        else -> ""
                                    },
                                onValueChange = {},
                                type = type,
                                onClearClick = {},
                                subText = "서브 텍스트를 입력해주세요",
                                descriptionText = "링크 입력을 원하지 않으면 넘어가도 괜찮아요.",
                            )
                        }
                    }
                },
            ),
    )
