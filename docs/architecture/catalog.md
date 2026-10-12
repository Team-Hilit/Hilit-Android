# Design System Catalog

이 문서는 Compose Multiplatform Web/WASM Catalog의 책임, Story와 Catalog Controls 계약을
정의한다. 공용 UI 계약은 [`design-system.md`](design-system.md), build configuration은
[`build-conventions.md`](build-conventions.md)를 함께 따른다.

## 1. 목적과 경계

`catalog`는 React Storybook과 유사한 Design System 검수 및 커뮤니케이션 도구다. Web/WASM
산출물로 디자이너가 구현 UI와 상태를 브라우저에서 검토할 수 있게 한다.
Catalog는 GitHub Pages 등 정적 호스팅 환경에 배포 가능한 Web/WASM 산출물을 생성한다. 이는
산출물의 성격과 module 책임을 정의하며 실제 배포 실행, 배포 대상 선택, 인증 정보 사용 또는
외부 서비스 변경 권한을 부여하지 않는다.

`catalog`는 제품 앱의 runtime Feature가 아니므로 다음을 포함하지 않는다.

- Android Framework API
- `app` 의존
- Android Navigation
- Hilt ViewModel 또는 Android Lifecycle dependency
- 실제 API 호출
- 제품 runtime business logic
- 실제 사용자 데이터

기본 Story 대상은 `designsystem` Composable이다. Feature UI를 노출해야 하면 ViewModel,
Hilt와 Android Lifecycle에 의존하는 Screen이 아니라 Android-independent Content-level UI를
사용한다.

## 2. Theme과 리소스

- Catalog shell의 `CatalogTheme`, color와 font는 도구 UI 전용으로 유지한다.
- 실제 Story content 영역만 `HilitTheme`으로 감싼다.
- Catalog shell dark mode는 Story의 제품 token을 변경하지 않는다.
- Catalog 전용 Theme, font, favicon과 Web entry resource는 `catalog`가 소유한다.
- Catalog 전용 리소스를 `core:resources`로 이동하지 않는다.
- `catalog`는 `core:resources`에 직접 의존하지 않는다.

제품 color나 typography token이 바뀌면 Color와 Typography Foundation Story를 갱신해 token
이름, 값과 렌더링 결과를 검수할 수 있게 한다.

## 3. Story 소유권

Story metadata, initial state와 Registry 등록은 `catalog`가 직접 소유한다. Code generation은
Story를 생성하지 않고 Controls에 필요한 반복 코드만 생성한다.

Story는 다음 조건을 충족한다.

- 모든 `StoryGroup`은 예외 없이 `id = "default"`인 기본 Story를 정확히 하나 포함한다.
- 합성·비식별 데이터만 사용한다.
- 실제 API, ViewModel과 제품 runtime dependency를 사용하지 않는다.
- component의 대표 상태와 필요한 추가 상태를 검토할 수 있게 한다.
- public component의 API나 상호작용이 바뀌면 같은 변경에서 갱신한다.

기본 Story 여부는 표시용 `title`이 아니라 `id`로 판단한다. 기본 Story가 누락되면 Story 등록
검증에서 예외가 발생하여 카탈로그 초기화가 중단된다.

## 4. Catalog Controls adapter

`designsystem`의 실제 Composable에 `@CatalogControls`를 추가하지 않는다. `catalog`에
조작할 값만 매개변수로 받는 non-local top-level `internal` adapter Composable을 작성하고
그 adapter에 annotation을 적용한다.

```kotlin
@CatalogControls
@Composable
internal fun SampleCatalogAdapter(
    label: String,
    enabled: Boolean,
) {
    SampleComponent(
        label = label,
        enabled = enabled,
        onClick = {},
    )
}
```

adapter 규칙은 다음과 같다.

- 실제 Composable은 named argument로 호출한다.
- 일대일 대응하는 매개변수는 같은 이름을 사용한다.
- 이름이 다르거나 값을 변환·조합하면 mapping 의도가 코드에 드러나야 한다.
- callback과 `Modifier`처럼 Controls로 조작하지 않을 값은 adapter parameter에서 제외한다.
- KSP는 함수 본문의 argument mapping을 검증하지 않는다. Kotlin compiler와 code review로
  확인한다.

## 5. Generated contract

Catalog Wasm KSP compilation은 adapter와 같은 package에 다음 선언을 생성한다.

- `${AdapterSimpleName}Args`
- `${AdapterSimpleName}Controls`

이 선언은 compilation 전 source에 존재하지 않는다. Adapter와 Story를 함께 작성하고 같은
compilation에서 해결할 수 있으며 중간 build가 필수는 아니다. IDE에서 generated declaration이
필요하면 다음을 실행한다.

```text
./gradlew :catalog:compileKotlinWasmJs
```

`@CatalogControls`는 `catalog:annotations`, JVM KSP processor는 `catalog:processor`가 소유한다.
`designsystem`은 두 모듈에 의존하지 않는다.

## 6. Controls Runtime

Generated code는 Material UI를 직접 조립하지 않고 `catalog.controls.runtime`의 Controls,
layout과 error UI를 호출한다.

- 별도 `:catalog:runtime` 모듈을 만들지 않는다.
- `catalog:processor`는 `catalog`에 의존하지 않고 안정적인 Runtime package와 함수 계약을
  대상으로 code를 생성한다.
- Kotlin source generation에는 `kotlinpoet-ksp`를 사용한다.
- processor dependency는 제품 앱, `designsystem` 또는 Catalog Wasm runtime에 포함하지 않는다.

각 parameter control은 공통 `CatalogControlField`로 감싼다. 공통 field는 parameter 이름을
title hierarchy, Kotlin type 이름을 body hierarchy로 표시한다. 실제 input UI는 parameter
이름을 반복하지 않는다.

지원 타입은 다음과 같다.

- `String`
- `Boolean`
- `Byte`, `Short`, `Int`, `Long`
- `Float`, `Double`
- `Enum`

KSP processor는 number control 생성 시 정확한 number type을 Runtime에 전달한다. Runtime은
error text나 input value로 number type을 추론하지 않는다. Type별 표시 이름과 설명은 Runtime의
단일 metadata contract에서 관리한다. Parameter별 설명을 위한 별도 annotation은 현재 계약에
포함하지 않는다.

## 7. 검증

Catalog 또는 Design System 변경이 Web/WASM 산출물에 영향을 주면 다음을 실행한다.

```text
./gradlew :catalog:wasmJsBrowserDistribution
```

검증 실행과 보고 절차는 [`AGENTS.md`](../../AGENTS.md)를 따른다.
