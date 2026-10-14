package stories.components.designsystem.hilitjdtextfield

import type.Story
import type.StoryGroup

internal val HilitJDTextFieldStories =
    StoryGroup(
        path = "Components/HilitJDTextField",
        description = "JD 입력용 멀티라인 텍스트 필드. 글자 수 카운터를 포함한다.",
        stories =
            listOf(
                Story(
                    id = "default",
                    title = "기본",
                    description = "비어 있는 기본 상태. Controls로 값·placeholder·maxLength를 조절할 수 있다.",
                ) {
                    HilitJDTextFieldCatalogAdapterControls(
                        initialArgs =
                            HilitJDTextFieldCatalogAdapterArgs(
                                value = "",
                                placeholder = "텍스트를 입력해주세요",
                                maxLength = 300,
                                minLength = 0,
                                validationErrorText = "",
                                explicitErrorText = "",
                                descriptionText = "작성하지 않아도 면접을 시작할 수 있어요",
                                validationSuccessText = "",
                            ),
                    )
                },
                Story(
                    id = "filled",
                    title = "입력됨",
                    description = "검증 조건이 없으면 입력 후에도 안내를 표시한다.",
                ) {
                    HilitJDTextFieldCatalogAdapterControls(
                        initialArgs =
                            HilitJDTextFieldCatalogAdapterArgs(
                                value = "백엔드 개발자 JD를 입력하는 예시입니다.",
                                placeholder = "텍스트를 입력해주세요",
                                maxLength = 300,
                                minLength = 0,
                                validationErrorText = "",
                                explicitErrorText = "",
                                descriptionText = "작성하지 않아도 면접을 시작할 수 있어요",
                                validationSuccessText = "",
                            ),
                    )
                },
                Story(
                    id = "validation-error",
                    title = "조건 미충족",
                    description = "길이 오류가 안내보다 먼저 표시된다.",
                ) {
                    HilitJDTextFieldCatalogAdapterControls(
                        initialArgs =
                            HilitJDTextFieldCatalogAdapterArgs(
                                value = "JD 입력 예시입니다.",
                                placeholder = "텍스트를 입력해주세요",
                                maxLength = 3000,
                                minLength = 200,
                                validationErrorText = "공고 내용은 200자 이상으로 입력해 주세요",
                                explicitErrorText = "",
                                descriptionText = "작성하지 않아도 면접을 시작할 수 있어요",
                                validationSuccessText = "공고 내용을 확인했어요",
                            ),
                    )
                },
                Story(
                    id = "validation-success",
                    title = "조건 충족",
                    description = "길이 검증 성공 메시지가 안내보다 먼저 표시된다.",
                ) {
                    HilitJDTextFieldCatalogAdapterControls(
                        initialArgs =
                            HilitJDTextFieldCatalogAdapterArgs(
                                value =
                                    "당사 서비스의 백엔드 API를 설계하고 운영하실 시니어 개발자를 찾고 있습니다. " +
                                        "Kotlin, Spring Boot 기반 마이크로서비스 경험이 필요하며, " +
                                        "대용량 트래픽 처리와 데이터 파이프라인 구축 경험을 우대합니다. " +
                                        "협업과 코드 리뷰 문화를 중시하는 팀에서 함께 성장할 분을 기다립니다. " +
                                        "서비스 품질을 개선하고 안정적인 배포를 위해 자동화 테스트와 모니터링을 함께 구축합니다.",
                                placeholder = "텍스트를 입력해주세요",
                                maxLength = 3000,
                                minLength = 200,
                                validationErrorText = "공고 내용은 200자 이상으로 입력해 주세요",
                                explicitErrorText = "",
                                descriptionText = "작성하지 않아도 면접을 시작할 수 있어요",
                                validationSuccessText = "공고 내용을 확인했어요",
                            ),
                    )
                },
                Story(
                    id = "explicit-error",
                    title = "명시적 오류 우선",
                    description = "명시적 오류가 길이 검증과 안내보다 우선한다. Controls에서 오류를 지우면 안내가 복원된다.",
                ) {
                    HilitJDTextFieldCatalogAdapterControls(
                        initialArgs =
                            HilitJDTextFieldCatalogAdapterArgs(
                                value = "프로젝트에서 API 개발을 담당했습니다.",
                                placeholder = "텍스트를 입력해주세요",
                                maxLength = 300,
                                minLength = 0,
                                validationErrorText = "",
                                validationSuccessText = "",
                                explicitErrorText = "포트폴리오에서 그 내용을 찾지 못했어요.",
                                descriptionText = "작성하지 않아도 면접을 시작할 수 있어요",
                            ),
                    )
                },
            ),
    )
