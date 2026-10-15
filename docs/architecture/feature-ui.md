# Feature UI와 MVI

이 문서는 Feature 화면의 MVI Contract, ViewModel, Screen, Content와 Preview 작성 계약을
정의한다. 모듈 배치는 [`module-system.md`](module-system.md), 화면 이동은
[`navigation.md`](navigation.md), 오류 분기는 [`error-handling.md`](error-handling.md)를 함께
따른다.

## 1. 화면 구성

각 화면은 다음 책임을 분리한다.

| 구성 요소 | 책임 |
|---|---|
| `Intent` | 사용자 액션 또는 UI lifecycle-triggered event |
| `State` | 화면에 지속적으로 표시되는 데이터 |
| `Effect` | 한 번만 소비되는 Navigation, Toast, Snackbar, Modal 요청 |
| `ViewModel` | 단일 State·Effect와 작업 수명 소유, Intent 라우팅과 흐름 조정 |
| `Handler` | 복잡한 화면의 책임별 UseCase 호출, 작업·취소 관리와 Effect 발행 |
| `Mutation` | Feature 내부 처리 결과와 상태 변경 사유 |
| `Reducer` | 현재 State와 Mutation으로 다음 immutable State를 계산 |
| `Screen` | ViewModel 연결, State 구독, Intent 전달, Effect 수집 |
| `Content` | ViewModel이 없는 순수 UI 렌더링 |

## 2. Feature 파일 구조

route 계약을 외부에 공개하지 않는 단일 모듈 Feature의 기본 구조는 다음과 같다.

```text
feature/{name}/
├── component/
├── extension/
├── navigation/
├── di/
├── {Name}Contract.kt
├── {Name}ViewModel.kt
└── {Name}Screen.kt
```

다른 모듈에 route 계약을 공개하면 `api`와 `impl`로 분리한다.

```text
feature/{name}/
├── api/
│   └── .../{Name}Route.kt
└── impl/
    └── ...
        ├── component/
        ├── extension/
        ├── navigation/
        ├── di/
        ├── {Name}Contract.kt
        ├── {Name}ViewModel.kt
        └── {Name}Screen.kt
```

`component`와 `extension`은 해당 Feature 내부에서만 사용한다. 재사용 범위가 넓어지면
[`module-system.md`](module-system.md)의 공통 모듈 이동 기준을 적용한다.

간단한 화면은 ViewModel 파일 안에 비공개 Mutation과 Reducer를 함께 둔다. 함수가 많다는
이유만으로 Handler를 추가하지 않는다. 서로 다른 작업 수명, 재시도 또는 취소 책임이
섞이는 화면은 다음처럼 필요한 단위만 분리한다.

```text
interview/
├── InterviewContract.kt             # 외부 Intent·State·Effect
├── InterviewViewModel.kt            # 라우팅과 Handler 사이 흐름 연결
├── InterviewMutation.kt             # 내부 변경 사유
├── InterviewReducer.kt              # 단일 상태 계산 진입점
├── InterviewPreparationHandler.kt   # 장치·질문 준비
├── InterviewTurnHandler.kt          # 질문·답변 제출
└── ...                             # 독립적인 작업 책임이 있는 경우만 추가
```

Handler와 Reducer는 해당 화면 내부 구현이다. 줄 수를 기준으로 기존 함수를 그대로 다른
거대 클래스에 옮기거나, 모든 화면에 같은 수의 Handler를 강제하지 않는다.

## 3. Contract

### 3.1 Intent

Intent는 사용자 행동, UI lifecycle event 또는 Screen이 요청한 Android 작업의 결과 callback을
표현하고 ViewModel의 외부 event 진입점을 `onIntent()` 하나로 통일한다.

```kotlin
sealed interface SampleIntent : MviIntent {
    data object Load : SampleIntent
    data object Refresh : SampleIntent
    data class ClickItem(val id: Long) : SampleIntent
    data class ReportPermissionResult(val isGranted: Boolean) : SampleIntent
}
```

- Intent 구현 시, 네이밍 컨벤션은 "V + O"(동사 + 목적어, 예: `ClickClose`, `ClickItem`, `ToggleReport`)의 형태로 작성한다.
- State 전체를 Intent에 담지 않는다.
- 처리에 필요한 최소 값만 전달한다.
- 이름은 사용자의 행동, lifecycle event 또는 Android 작업 결과가 드러나게 작성한다.
- Android 작업 결과 callback은 `Report...`처럼 결과 보고임을 드러내고 Android Framework,
  CameraX, Media3, WorkManager 객체나 실제 사용자 미디어를 Intent에 담지 않는다.
- 최초 로드도 필요한 경우 명시적 Intent로 표현한다.
- Intent가 많은 복잡한 화면만 `Preparation`, `Turn` 같은 sealed 하위 책임 그룹으로 묶는다.
  ViewModel과 Handler의 `when`은 누락을 컴파일러가 확인하도록 `else` 없이 작성한다.
- UseCase 응답, 폴링 결과와 내부 작업 완료는 Mutation 또는 내부 흐름 callback으로 전달한다.
  외부 사용자·Android 입력이 아닌 값을 Intent로 재포장하지 않는다.
- 초기화 인자는 `Load(sessionId)`처럼 Intent에 포함하고 별도의 `bind...()` 진입점을 만들지 않는다.

### 3.2 State

State는 화면에 지속적으로 표시할 값을 하나의 immutable `data class`로 표현한다.

```kotlin
data class SampleState(
    val isLoading: Boolean = false,
    val items: List<SampleUiModel> = emptyList(),
    val errorMessage: String? = null,
) : MviState
```

- 상태 변경은 `MviContext.dispatch(mutation)`에서 화면의 단일 순수 Reducer를 통해 수행한다.
  Reducer는 `copy()`로 새 상태를 반환한다. ViewModel과 Handler에서 직접 상태를 복사해 저장하지 않는다.
- Boolean은 `is`, `has`, `can`, `should`처럼 의미 있는 접두어를 사용한다.
- 한 번 실행하고 사라져야 하는 동작은 State에 넣지 않는다.

### 3.3 Effect

Effect는 한 번만 소비되어야 하는 사건을 표현한다.

```kotlin
sealed interface SampleEffect : MviEffect {
    data object SubmissionSucceeded : SampleEffect
    data class ShowToast(val message: String) : SampleEffect
}
```

대표적인 Effect는 Navigation을 유발하는 Feature 결과, Toast, Snackbar와 일회성 Modal
요청이다. recomposition으로 반복 실행되면 안 되는 동작은 State가 아니라 Effect로 처리한다.

### 3.4 State와 Effect 선택

| 질문 | 선택 |
|---|---|
| 화면에 계속 남아야 하는가? | State |
| 한 번 소비되면 사라져야 하는가? | Effect |
| recomposition으로 반복 실행되면 안 되는가? | Effect |
| 화면별 오류 UI로 지속되어야 하는가? | State |

### 3.5 Feature 책임을 드러내는 이름

Intent와 Effect 이름은 다른 Feature의 목적지가 아니라 현재 Feature에서 발생한 사건을
표현한다. 예를 들어 Login Feature는 `NavigateToHome`보다 `LoginSucceeded`를 발행한다. Screen은
이를 상위 callback으로 전달하고 실제 이동 목적지는 `app` 계층이 결정한다.

## 4. ViewModel

### 4.1 단일 상태 변경 경로

외부 입력은 `onIntent()`로 받고, 내부 변경은 아래 경로로 처리한다. 별도 MVI 라이브러리나
Handler 등록 프레임워크를 도입하지 않는다.

```text
Screen → Intent → ViewModel → (필요하면 Handler) → Mutation → Reducer → State
                             ViewModel / Handler → Effect → Screen → 상위 callback
```

```kotlin
private sealed interface SampleMutation {
    data object LoadStarted : SampleMutation
    data object LoadFinished : SampleMutation
    data class ItemsLoaded(val items: List<SampleUiModel>) : SampleMutation
    data class LoadFailed(val message: String?) : SampleMutation
}

private object SampleReducer : MviReducer<SampleState, SampleMutation> {
    override fun reduce(state: SampleState, mutation: SampleMutation): SampleState =
        when (mutation) {
            SampleMutation.LoadStarted -> state.copy(isLoading = true, errorMessage = null)
            SampleMutation.LoadFinished -> state.copy(isLoading = false)
            is SampleMutation.ItemsLoaded -> state.copy(isLoading = false, items = mutation.items)
            is SampleMutation.LoadFailed -> state.copy(isLoading = false, errorMessage = mutation.message)
        }
}

class SampleViewModel(...) : MviViewModel<SampleIntent, SampleState, SampleEffect>(SampleState()) {
    private val context = createContext(SampleReducer)

    override fun onIntent(intent: SampleIntent) {
        when (intent) {
            SampleIntent.Load, SampleIntent.Refresh -> load()
            is SampleIntent.ClickItem -> ...
            is SampleIntent.ReportPermissionResult -> ...
        }
    }

    private fun load() {
        context.dispatch(SampleMutation.LoadStarted)
        context.launch {
            // UseCase 결과를 UiModel로 변환한 뒤 전달한다. 오류 정책은 error-handling.md를 따른다.
            context.dispatch(SampleMutation.ItemsLoaded(...))
        }
    }
}
```

- Mutation은 `LoadStarted`, `ItemsLoaded`처럼 변경 사유와 필요한 최소 값을 표현한다.
  임의 상태 변경 lambda나 State 전체를 담는 범용 `Update`/`ReplaceState`를 만들지 않는다.
- Reducer는 IO, UseCase, 현재 시각 조회, coroutine 실행, Effect 발행과 mutable helper 변경을 하지 않는다.
  `StateFlow.update`가 계산을 재실행할 수 있으므로 같은 입력은 같은 결과를 반환해야 한다.
- 하나의 root Reducer가 책임별 순수 계산 함수에 위임할 수는 있지만 상태를 저장하는 곳은 화면당
  하나다. Handler별 StateFlow나 별도의 UI 상태 원본을 만들지 않는다.
- `createContext(reducer)`는 ViewModel당 한 번 호출하고 같은 문맥을 모든 Handler에 전달한다.
  `dispatch()`는 동기적으로 반영하므로 후속 Effect 이전에 새 State를 읽을 수 있다.

### 4.2 Handler와 작업 수명

```kotlin
private class SampleLoadHandler(
    private val context: MviContext<SampleState, SampleMutation, SampleEffect>,
    private val loadItems: LoadItemsUseCase,
    private val onPrepared: () -> Unit,
) {
    private var loadJob: Job? = null
    private var loadVersion = 0

    fun load() {
        loadJob?.cancel()
        val version = ++loadVersion
        context.dispatch(SampleMutation.LoadStarted)
        loadJob = context.launch {
            val items = loadItems() // 예시는 성공 경로. 실제 구현은 기존 오류 정책을 적용한다.
            currentCoroutineContext().ensureActive()
            if (version != loadVersion) return@launch
            context.dispatch(SampleMutation.ItemsLoaded(items.map { it.toUiModel() }))
            onPrepared()
        }
    }
}
```

- Handler는 일반 Kotlin 클래스로 만들고 필요한 UseCase와 문맥만 받는다. ViewModel은 필요한
  Handler를 직접 조립하거나 Hilt로 공급받되 화면 수명 밖에서 공유되는 mutable singleton으로 만들지 않는다.
- Handler 사이에 직접 참조를 주지 않는다. 다음 책임으로 넘어갈 때는 의미가 분명한 callback이나
  내부 결과 타입을 ViewModel에 전달하고 ViewModel이 다음 Handler를 호출한다.
- Intent 처리·Mutation 전달·Effect 발행과 런타임 플래그 접근은 Main에서 실행한다.
  IO는 자식 작업 안의 `withContext`로 전환하고 결과 반영 전에 Main으로 돌아온다.
- 작업은 `context.launch`로 시작한다. 전역 Scope나 Handler 자체 Scope를 만들지 않는다.
  ViewModel이 제거되면 진행 중 요청, 폴링과 Modal 대기도 함께 취소된다.
- Job, 요청 세대 번호와 제출 잠금은 내부 작업 제어용으로만 둔다. UI 표시값은 State에서 읽는다.
  대체 가능한 검색·검증은 이전 작업 취소와 입력 또는 세대 검증으로 오래된 결과를 차단한다.
- 제출·삭제처럼 중복 실행하면 안 되는 작업의 가드는 suspend 이전에 설정한다. 장시간 작업이
  타이머와 Android 결과 callback까지 막는 단일 전역 큐를 만들지 않는다.
- `CancellationException`을 오류 UI로 변환하지 않고 다시 던진다. 취소가 파일·세션 정리 등
  제품 정책을 바꾸지 않도록 기존 책임과 경계를 유지한다.

### 4.3 Effect와 경계

- ViewModel 또는 Handler는 `domain`의 UseCase를 호출하고 Repository 구현체에 직접 접근하지 않는다.
- 화면에 필요한 경우 Domain Model을 UiModel로 변환한다.
- Effect는 `core:common`의 `MviViewModel`과 같은 `MviContext`를 통해 발행한다.
- `sendEffect()`는 `Channel.trySend()`로 즉시(non-blocking) 발행해 별도 코루틴 생성 없이
  호출 순서와 채널 진입 순서를 일치시킨다. 동시 발행량이 `Channel.BUFFERED` 용량을 넘거나
  채널이 닫혀 발행이 실패하면 Effect는 유실되며, 경고 로그만 남기고 앱을 크래시시키지
  않는다.
- Navigation을 직접 실행하지 않고 Feature 결과 Effect를 발행한다.
- 오류 유형에 따른 처리는 [`error-handling.md`](error-handling.md)를 따른다.

### 4.4 작성 순서와 검증

1. 외부 입력과 지속 State·일회성 Effect를 Contract에 정의한다.
2. 의미 있는 Mutation과 순수 Reducer를 작성한다.
3. 독립적인 작업 수명과 취소 책임이 있는 부분만 Handler로 분리한다.
4. ViewModel에서 단일 문맥을 조립하고 Intent 라우팅과 책임 간 흐름을 연결한다.
5. Screen의 입력·Android 결과와 테스트를 함께 갱신한다.

Reducer는 관련 없는 상태 보존과 상태 전이를 순수 테스트로 검증한다. 복잡한 Handler는
가짜 `MviContext`와 UseCase를 사용해 중복 제출, 오래된 비동기 결과와 취소를 검증한다.
기존 ViewModel 통합 테스트는 Intent부터 State·Effect까지의 동작 보존 근거로 유지한다.

## 5. Screen과 Content

Screen은 ViewModel을 연결하고 실제 UI는 ViewModel-free Content로 분리한다.

```kotlin
@Composable
fun SampleScreen(
    onCompleted: () -> Unit,
    viewModel: SampleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.onIntent(SampleIntent.Load)
        viewModel.effect.collect { effect ->
            when (effect) {
                SampleEffect.SubmissionSucceeded -> onCompleted()
                is SampleEffect.ShowToast -> ...
            }
        }
    }

    SampleContent(
        state = state,
        onIntent = viewModel::onIntent,
    )
}

@Composable
fun SampleContent(
    state: SampleState,
    onIntent: (SampleIntent) -> Unit,
) {
    ...
}
```

- Screen은 State 구독, Intent 전달과 Effect 수집을 담당한다.
- Content는 전달된 값과 callback만으로 UI를 렌더링한다.
- Figma MCP 서버를 통해 참조/가져온 디자인을 바탕으로 Composable UI(Screen, Content, Component)를 구현할 경우, KDoc 최상단에 해당 Figma 노드 ID/번호(예: `Figma Node: 1234:5678`)를 반드시 병기한다.
- 필요한 최초 로드는 `LaunchedEffect(Unit)`에서 `Load` Intent로 전달한다.
- Content는 Hilt, Android Lifecycle, Navigation, network, file access와 실제 사용자 데이터에
  의존하지 않는다.

## 6. Preview와 Catalog

Preview와 Catalog는 ViewModel-free UI만 렌더링한다.

| 모듈 | Preview 대상 |
|---|---|
| `app` | 앱 Theme과 ViewModel-free 전역 UI |
| `feature:*:impl` | ViewModel-free Content UI |
| `designsystem` | `commonMain` 공용 component와 Theme |

- Android와 Compose Multiplatform `commonMain`은
  `androidx.compose.ui.tooling.preview.Preview` annotation을 사용한다.
- `core:permission` platform launcher, `core:resources` accessor와 `catalog` Wasm UI에는 Preview
  Convention Plugin을 적용하지 않는다.
- Catalog 검수는 Web/WASM Story를 사용한다.
- Preview와 Catalog에 실제 사용자 데이터나 제품 runtime dependency를 포함하지 않는다.
- Plugin 적용 대상은 [`build-conventions.md`](build-conventions.md)를 따른다.
