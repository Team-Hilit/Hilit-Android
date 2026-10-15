# Error Handling

이 문서는 오류 분류, 레이어별 전달 책임과 UI 처리 정책을 정의한다. Feature의 State와 Effect는
[`feature-ui.md`](feature-ui.md), 앱 전역 Modal은 [`global-modal.md`](global-modal.md)를 함께
따른다.

## 1. 오류 분류

| 타입 | 예시 | UI 처리 | 전달 방식 |
|---|---|---|---|
| `NetworkUnavailable` | 인터넷 연결 없음, timeout, DNS 실패 | 기본은 앱 종료 Modal. 명시적 복구 계약이 있는 Interview 흐름은 해당 복구 UI 또는 지속 작업 상태 | 기본은 Global Event. 승인된 Interview 복구 흐름은 Feature State/Effect 또는 지속 Android 작업 상태 |
| `ServerError` | HTTP 500, 502, 503 | 기본은 앱 종료 Modal. 안전한 재시도가 API 계약에 명시된 Interview 흐름은 해당 복구 UI 또는 지속 작업 상태 | 기본은 Global Event. 승인된 Interview 복구 흐름은 Feature State/Effect 또는 지속 Android 작업 상태 |
| `ClientError` | HTTP 400, 401, 404 | 화면별 기획에 따른 UI | Feature State 또는 Effect |
| `Unknown` | 분류할 수 없는 Exception | Toast | Global Event |

## 2. 처리 위치 선택

- 화면별 message나 UI가 다르면 Feature State로 처리한다.
- 한 번만 표시할 화면별 알림은 Feature Effect로 처리한다.
- 앱 전체에서 동일한 Modal, Toast 또는 Snackbar로 처리하면 Global Event를 사용한다.
- 재시도나 앱 종료처럼 app-level action이 필요하면 기본적으로 Global Event를 사용하되, 아래 Interview 예외 조건을 충족하는 재시도는 Feature State/Effect 또는 지속 작업 상태를 사용한다.
- Client Error를 기본 Global Event로 변환하지 않는다.

### 2.1 Interview Network·Server Error 예외

Interview Feature 흐름의 복구 가능한 Network Error와 Server Error는 다음 조건을 모두 만족할 때만 Global Event 대신 Interview Feature State/Effect 또는 지속 Android 작업 상태로 처리할 수 있다.

- 이 저장소의 면접 계약 문서가 해당 오류의 재시도·재개·중단·백그라운드 작업 복구 동작을 명시한다.
- 복구에 필요한 로컬 세션과 미디어 체크포인트를 안전하게 저장한다.
- Server Error는 서버 또는 API 계약이 중복 부수 효과 없이 같은 요청을 안전하게 재시도할 수 있다고 명시한다.
- Feature State/Effect는 `feature:interview`가, 지속 Android 작업 상태는 승인된 작업 소유 계층이 관리한다.
- 복구 경로가 없는 Interview Network Error와 Interview 밖의 모든 Network Error는 기존 Global Event를 사용한다.
- 안전한 재시도와 복구 경로가 명시되지 않은 Interview Server Error와 Interview 밖의 모든 Server Error는 기존 Global Event를 사용한다.

이 예외는 Unknown Error까지 Feature가 처리하도록 확장하지 않는다. Unknown Error는 계속 Global Event를 사용한다.

## 3. 레이어별 책임

| 레이어 | 책임 |
|---|---|
| `data` | 외부 Exception을 프로젝트 error type으로 변환하거나 상위로 전파 |
| `domain` | UseCase 결과와 UI 독립 error를 전달하고 UI 표현은 결정하지 않음 |
| `feature:*` | Client Error를 State/Effect로 처리하고 Network/Server/Unknown을 Global Event로 전달. 단, 위 조건을 만족하는 Interview Network·Server Error는 Interview State/Effect 또는 지속 작업 상태로 처리 |
| `core:common` | 플랫폼 독립 공통 Error, `GlobalAppEvent`, `GlobalErrorHandler` 계약 |
| `app` | Global Event를 collect하고 Modal, Toast 또는 Snackbar 렌더링 |

새로운 오류 처리 mechanism을 임의로 만들지 않고 기존 Global Event 정책을 사용한다.

## 4. 공통 오류와 Event

공통 타입은 Android에 의존하지 않는다. 다음 코드는 계약의 핵심만 보여주는 개념 예시다.

```kotlin
open class CustomException(
    val errCode: Int,
    override val message: String,
) : Exception(message)

class NetworkUnavailableException(...) : CustomException(...)
class ServerException(...) : CustomException(...)
class ClientException(...) : CustomException(...)
class UnknownException(...) : CustomException(...)
```

```kotlin
sealed interface GlobalAppEvent {
    data object ShowNetworkErrorAndExit : GlobalAppEvent
    data object ShowServerErrorAndExit : GlobalAppEvent
    data object ShowUnknownError : GlobalAppEvent
}

data class GlobalAppEventEnvelope(
    val event: GlobalAppEvent,
    val deliveryId: String? = null,
)
```

Feature는 오류를 분류해 해당 `GlobalAppEvent`를 선택하고, `GlobalErrorHandler`는 선택된 event를
app에 전달하는 단일 hub 역할만 한다. `core:common`이 `domain` 예외 타입에 의존해 오류를 다시
분류하지 않는다.

```kotlin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object GlobalErrorHandler {
    private val _events = MutableSharedFlow<GlobalAppEventEnvelope>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GlobalAppEventEnvelope> = _events.asSharedFlow()

    suspend fun emit(event: GlobalAppEvent, deliveryId: String? = null) {
        _events.emit(GlobalAppEventEnvelope(event, deliveryId))
    }
}
```

SharedFlow를 사용하는 구현은 collect 이전 event를 위한 `extraBufferCapacity = 1`과 연속 실패 시
최신 event를 우선하는 `DROP_OLDEST` 정책을 사용한다.

## 5. ViewModel 처리

ViewModel 또는 위임받은 Feature 내부 Handler는 loading 상태를 복구하는 Mutation을 전달한
뒤 error type에 따라 화면 또는 전역 경로를 선택한다. 책임을 Handler로 옮겨도 오류 분류와
전달 정책은 동일하며, Reducer는 오류 표시용 상태만 계산한다.

```kotlin
context.dispatch(SampleMutation.LoadFinished)
when (throwable) {
    is ClientException -> context.dispatch(SampleMutation.LoadFailed(throwable.message))

    is NetworkUnavailableException ->
        GlobalErrorHandler.emit(GlobalAppEvent.ShowNetworkErrorAndExit)

    is ServerException ->
        GlobalErrorHandler.emit(GlobalAppEvent.ShowServerErrorAndExit)

    is UnknownException ->
        GlobalErrorHandler.emit(GlobalAppEvent.ShowUnknownError)

    else -> GlobalErrorHandler.emit(GlobalAppEvent.ShowUnknownError)
}
```

ViewModel은 Android UI를 직접 표시하지 않는다.

Interview ViewModel은 문서화된 복구 경로가 있는 `NetworkUnavailableException`에 한해 로컬 체크포인트 저장 뒤 Interview State/Effect로 전환할 수 있다. `ServerException`은 해당 API가 중복 부수 효과 없는 재시도를 명시하고 면접 계약 문서가 복구 동작을 정의한 경우에만 같은 방식으로 전환한다. 조건을 충족하지 못한 Network Error는 `ShowNetworkErrorAndExit`, Server Error는 `ShowServerErrorAndExit`를 발행한다. 백그라운드 미디어 업로드처럼 화면 밖 지속 작업이 소유한 복구는 해당 작업 상태와 제약·재시도 정책을 사용한다.

## 6. 앱 루트 처리

`app`의 Root Composable은 Global Event를 한 곳에서 collect한다.

```kotlin
LaunchedEffect(Unit) {
    GlobalErrorHandler.events.collect { envelope ->
        when (envelope.event) {
            GlobalAppEvent.ShowNetworkErrorAndExit -> ...
            GlobalAppEvent.ShowServerErrorAndExit -> ...
            GlobalAppEvent.ShowUnknownError -> ...
        }
    }
}
```

- Network와 Server Event는 app-level 종료 Modal 정책에 연결한다.
- Unknown Event는 app-level Toast 또는 Snackbar에 연결한다.
- UI component는 `designsystem`, Android action과 root rendering은 `app`이 소유한다.
- 재시도 action은 현재 화면 또는 Feature 정책으로 다시 전달한다.
- 일반 오류의 `deliveryId`는 `null`이다. 영속 Worker 오류만 저장된 불투명 표시 확인 ID를 사용한다.
- app renderer는 오류 표시가 UI 상태에 반영된 뒤 `deliveryId`가 있을 때만 domain acknowledgment UseCase를 호출한다. `GlobalModalRequest`에는 이 ID나 작업 정보를 추가하지 않는다.

## 7. 금지 사항

- `domain`에서 Modal, Toast, Snackbar 문구나 UI 유형 결정
- `data`에서 UI event 발행 또는 UI 정책 encoding
- Feature에서 Network/Server/Unknown Error를 위한 별도 전역 mechanism 도입. 승인된 Interview Network·Server Error 예외도 전역 mechanism을 새로 만들지 않고 Feature State/Effect 또는 지속 작업 상태만 사용
- ViewModel에서 Android Toast나 Modal 직접 표시
- 민감한 error payload나 사용자 데이터를 plaintext log에 기록
