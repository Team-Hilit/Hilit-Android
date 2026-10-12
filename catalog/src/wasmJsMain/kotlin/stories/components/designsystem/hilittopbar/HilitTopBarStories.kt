package stories.components.designsystem.hilittopbar

import com.dminus14.designsystem.component.icon.HilitIconAsset
import com.dminus14.designsystem.component.topbar.TopBarType
import type.DEFAULT_STORY_ID
import type.Story
import type.StoryGroup

private val HilitIconTopBarStory =
    Story(
        id = "icon",
        title = "Icon",
        description = "좌·우 아이콘과 중앙 타이틀. Max / HideLeft / HideMiddle / HideRight.",
    ) {
        HilitIconTopBarCatalogAdapterControls(
            initialArgs =
                HilitIconTopBarCatalogAdapterArgs(
                    type = TopBarType.Max,
                    title = "타이틀",
                    leftIcon = HilitIconAsset.Cancel,
                    rightIcon = HilitIconAsset.Plus,
                ),
        )
    }

internal val HilitTopBarStories =
    StoryGroup(
        path = "Components/HilitTopBar",
        description =
            "TopBar chrome과 Icon / Text / Logo 전용 TopBar. TopBarType으로 좌·중앙·우 노출을 제어한다.",
        stories =
            listOf(
                HilitIconTopBarStory.copy(
                    id = DEFAULT_STORY_ID,
                    title = "Default",
                ),
                HilitIconTopBarStory,
                Story(
                    id = "text",
                    title = "Text",
                    description = "좌측 아이콘·중앙 타이틀·우측 미니 버튼. Max / HideLeft / HideMiddle.",
                ) {
                    HilitTextTopBarCatalogAdapterControls(
                        initialArgs =
                            HilitTextTopBarCatalogAdapterArgs(
                                type = TopBarType.Max,
                                title = "타이틀",
                                buttonText = "버튼",
                                leftIcon = HilitIconAsset.Cancel,
                            ),
                    )
                },
                Story(
                    id = "logo",
                    title = "Logo",
                    description = "좌측 Hilit 로고와 선택적 우측 아이콘. Max / HideRight.",
                ) {
                    HilitLogoTopBarCatalogAdapterControls(
                        initialArgs =
                            HilitLogoTopBarCatalogAdapterArgs(
                                type = TopBarType.Max,
                                showRightIcon = true,
                                rightIcon = HilitIconAsset.Profile,
                            ),
                    )
                },
            ),
    )
