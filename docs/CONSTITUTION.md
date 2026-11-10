# Project Constitution

This document defines the highest-level rules for the D-14 Android project. It exists to protect
product direction, user data, architecture boundaries, and long-term maintainability.

This document is normative. It must be followed by human contributors, AI coding agents, and any
automated workflow that changes this repository.

---

## 1. Document Authority

The project documents are interpreted in the following priority order:

1. `docs/CONSTITUTION.md`
2. `docs/ARCHITECTURE.md`
3. `AGENTS.md`
4. `README.md`

When documents conflict, the higher-priority document prevails.

`docs/CONSTITUTION.md` defines non-negotiable principles and prohibited changes.

`docs/ARCHITECTURE.md` defines module responsibilities, dependency rules, MVI structure, Navigation
3 structure, design system rules, catalog rules, error handling details, and project structure.

`AGENTS.md` defines execution rules for AI coding agents.

`README.md` defines the public-facing service overview.

---

## 2. Constitution Change Control

This document must not be modified without an explicit user request.

Even when a user requests a modification, the change must not be applied until the user explicitly
accepts the proposed modification.

AI coding agents must not silently amend, relax, reinterpret, or bypass this document.

If a requested implementation conflicts with this document, the implementation must stop and the
conflict must be reported.

---

## 3. Product Scope Principles

D-14 is an interview preparation service for job seekers and people preparing for job transitions.

The product exists to help users practice interviews, receive AI feedback, receive trusted human
feedback, and review feedback through reports.

Implementation decisions must preserve the service purpose. Technical convenience must not override
the interview preparation workflow, user trust, or data protection requirements.

---

## 4. User Data Protection

The following data is sensitive user data:

- PDF resumes
- Interview videos
- Interview audio
- STT transcripts of interview audio
- Interview feedback
- Any derivative report or analysis produced from the data above

Sensitive user data must not be externally transmitted unless the storage, transmission, sharing,
and logging policy for that data is explicitly defined.

Sensitive user data must not be stored long-term unless the storage, retention, deletion, and access
policy is explicitly defined.

Sensitive user data must not be logged in plaintext.

Sensitive user data must not be exposed through debug output, analytics events, crash reports, build
logs, CI logs, or catalog stories.

Sensitive user data must not be embedded in source code, tests, fixtures, screenshots, sample
assets, documentation, or prompt examples.

When a feature requires sensitive user data and the policy is incomplete or ambiguous, the
implementation must stop and the ambiguity must be reported immediately.

AI coding agents must not decide sensitive data behavior on behalf of the user.

For the avoidance of doubt, the Android client must implement, to the fullest extent reasonably
possible, all user data protection measures that fall within its scope and capabilities. This
project and its contributors, including AI coding agents, must neither participate in nor make
decisions concerning server-side implementation, server-side policy, or any other matter that falls
outside the Android client's scope of responsibility.

This scope limitation does not relax any user data protection requirement applicable to the Android
client, nor does it authorize the Android client to assume or determine server-side behavior. If a
compliant Android client implementation depends on an undefined or ambiguous server-side
implementation or policy, implementation must stop and the missing decision must be reported.

---

## 5. Architecture Principles

The project must follow Clean Architecture.

Dependencies must flow from outer layers toward inner layers.

Business logic must remain independent from UI implementation, Android framework APIs, and data
implementation details.

Architecture boundaries must not be weakened to reduce code volume, simplify tests, or speed up
implementation.

Temporary implementation is allowed only when it does not violate module boundaries, data protection
rules, or dependency rules.

---

## 6. Module Boundary Rules

### 6.1 `app`

`app` is the Android application entry and composition root.

`app` owns `Application`, Manifest registration, `MainActivity`, app-level Navigation 3 assembly,
and global UI event rendering.

`app` may depend on `data` only for composition root and Hilt binding purposes.

`app` must not use `data` repository implementations to perform business logic directly.

`app` must not own screen-specific UI state or feature business logic.

### 6.2 `feature:*`

`feature:*` owns screen-level MVI implementation and feature route or entry definitions.

`feature:*` may depend on `domain`, `designsystem`, `core:common`, and `core:resources`.

`feature:*` must not depend on `data`.

`feature:*` must not depend on `app`.

`feature:*` must not depend on another feature's `impl` module.

`feature:*` may depend on another feature's `api` module for route or entry contracts only.

Feature-to-feature navigation execution must still be assembled at the app root. Cross-feature `api`
dependencies must not be used to import screens, ViewModels, or implementation details from another
feature.

### 6.3 `domain`

`domain` must remain a pure Kotlin module.

`domain` must not depend on Android Framework APIs.

`domain` must not depend on `data`.

`domain` must not depend on `feature:*`.

`domain` must not decide UI rendering behavior.

### 6.4 `data`

`data` owns repository implementations, API interfaces, DTOs, remote data sources, local data
sources, Room components, DataStore access, and data-layer DI modules.

`data` may depend on `domain` and `core:common`.

`data` must not depend on `feature:*`.

`data` must not depend on `app`.

`data` must not encode UI policy.

### 6.5 `designsystem`

`designsystem` owns shared UI components and theme primitives.

`designsystem` must be implemented as a Compose Multiplatform-compatible module.

`designsystem` must not depend on Android Framework APIs.

`designsystem` must not depend on Hilt.

`designsystem` must not depend on Android Navigation.

`designsystem` must not depend on Android Lifecycle APIs.

`designsystem` must not use Android resource APIs directly.

`designsystem` must not directly call Toast, Activity, Context, Intent, or Android platform-specific
side effects.

Platform-specific behavior required by a shared component must be represented through
platform-independent state or callbacks and handled by `app` or `feature:*`.

`designsystem` must not depend on `feature:*` or `app`.

### 6.6 `catalog`

`catalog` exists for design system review and communication.

`catalog` is not a product runtime feature module.

`catalog` must remain compatible with Web/WASM output.

`catalog` must not depend on Android Framework APIs.

`catalog` must not depend on `app`.

`catalog` must not include Android Navigation, Hilt ViewModels, real API calls, or product runtime
logic.

Catalog stories must use `designsystem` components or Android-independent `Content`-level UI.

### 6.7 `core:common`

`core:common` must remain minimal.

Only code currently used or planned to be used by more than one module may be moved to
`core:common`.

`core:common` must not become a dumping ground for unrelated utilities, feature-specific models, or
product logic.

### 6.8 `core:resources`

`core:resources` owns Compose Multiplatform resources shared by `app`, `feature:*`, and
`designsystem`.

`core:resources` must remain compatible with Android and Web/WASM consumers.

`core:resources` must not use Android Framework resource APIs.

`core:resources` must not depend on Hilt, Android Navigation, Android Lifecycle APIs, `app`, or
`feature:*` implementations.

Catalog-specific resources are owned by `catalog` and must not be moved to `core:resources`.

---

## 7. Navigation Principles

The project uses Navigation 3.

A separate `:navigation` module must not be introduced.

A separate `:feature:navigator` module must not be introduced.

The `Navigator` back stack helper and Navigation 3 root assembly belong in the `app` module. A class
named
`Navigator` inside `app` is not a separate navigation module.

The app root in `app` assembles Navigation 3 entries and routes.

Feature modules define their own route or entry contracts and expose them upward.

Feature modules must not perform app-level navigation assembly.

ViewModels must not execute navigation directly. ViewModels emit navigation effects, and the UI
layer handles navigation execution.

---

## 8. UI and MVI Principles

Screen architecture must follow MVI.

Each screen must be organized around `Intent`, `State`, `Effect`, `ViewModel`, `Screen`, and
`Content` responsibilities.

User actions and lifecycle-triggered events must enter the ViewModel through `Intent`.

Persistent UI data must be represented as `State`.

One-time events such as navigation, Toast, Snackbar, and dialog requests must be represented as
`Effect` or a global app event according to the error handling rules.

State must be immutable and updated by state-copying semantics.

Screen-level composables must separate ViewModel-connected `Screen` logic from ViewModel-free
`Content` UI.

Previewable or catalog-exposed UI must not require Hilt, Android Lifecycle, Android Navigation, or a
real ViewModel instance.

---

## 9. Error Handling Principles

Client errors must be handled by the relevant feature through feature `State` or feature `Effect`.

Network errors must be handled through the global app event mechanism, except that recoverable network errors within Interview feature workflows may be handled through Interview feature State or Effect, or through persistent Android work state, when this repository explicitly defines the recovery behavior and safely persists required local session and media checkpoints. Interview network errors without an explicitly defined recovery path, and all other network errors outside Interview except those covered by the app-launch session check exception below, must continue to use the global app event mechanism.

Server errors must be handled through the global app event mechanism, except that recoverable server errors within Interview feature workflows may be handled through Interview feature State or Effect, or through persistent Android work state, when the server or API contract explicitly guarantees that the request can be safely retried without duplicate side effects and this repository explicitly defines the recovery behavior. Interview server errors without an explicitly documented safe-retry and recovery path, and all other server errors outside Interview except those covered by the app-launch session check exception below, must continue to use the global app event mechanism.

Network and server errors raised while the app-launch session check runs may be handled through the Splash feature State, with a retry action that re-runs the launch check, when this repository explicitly defines that recovery behavior. Stored tokens must be preserved in that case. This exception applies only to the app-launch session check and does not extend to network or server errors raised after the launch check completes.

Unknown errors must be handled through the global app event mechanism.

`domain` must not determine UI presentation for errors.

`data` must convert or propagate external exceptions according to the project error model.

`app` must collect global app events and render app-level Dialog, Toast, or Snackbar UI.

Error handling behavior must follow `docs/ARCHITECTURE.md` unless this document defines a stricter
rule.

---

## 10. Build and Dependency Governance

Gradle Convention Plugins are the standard mechanism for shared build configuration.

Repeated module build configuration must be extracted into `build-logic` when it becomes shared
policy.

Module dependencies must match the dependency rules defined by this document and
`docs/ARCHITECTURE.md`.

No dependency may be added only because it makes a local implementation easier.

Any dependency that affects architecture boundaries, user data handling, media processing, AI
integration, networking, logging, analytics, or storage requires explicit justification.

---

## 11. Prohibited Changes

The following changes are prohibited without explicit user approval and constitution-compliant
justification:

- Making `feature:*` depend on `data`
- Making `feature:*` depend on another feature's `impl` module
- Making `feature:*` depend on `app`
- Making `domain` depend on Android Framework APIs
- Making `domain` depend on `data` or `feature:*`
- Making `data` depend on `feature:*` or `app`
- Adding Android Framework dependencies to `designsystem`
- Adding Android Framework dependencies to `catalog`
- Adding a separate `:navigation` module
- Adding a separate `:feature:navigator` module
- Moving app-level Navigation 3 assembly out of `app`
- Replacing MVI as the screen architecture
- Logging sensitive user data
- Externally transmitting sensitive user data without an explicit policy
- Storing sensitive user data long-term without an explicit policy
- Using real user data in source code, tests, fixtures, screenshots, catalog stories, documentation,
  or examples
- Modifying this document without the required approval process

---

## 12. Stop Conditions

Work must stop when any of the following conditions occurs:

- The requested change conflicts with this document.
- The requested change requires a new sensitive data policy that has not been defined.
- The requested change requires weakening module boundaries.
- The requested change requires changing the Navigation 3 ownership model.
- The requested change requires adding Android dependencies to `designsystem` or `catalog`.
- The requested change requires modifying this document.
- The implementation requires guessing product behavior, data retention behavior, sharing behavior,
  logging behavior, or external transmission behavior.

When work stops, the conflict or missing decision must be reported directly.

---

## 13. Interpretation Rule

This document must be interpreted strictly.

Silence in this document does not grant permission to violate `docs/ARCHITECTURE.md`.

Silence in `docs/ARCHITECTURE.md` does not grant permission to violate this document.

If a rule is ambiguous, the implementation must stop and request clarification.
