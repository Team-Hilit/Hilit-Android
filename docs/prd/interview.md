# 면접 서비스 사용자 플로우와 Android 구현 항목

## 1. 문서 목적

이 문서는 면접 UI와 실제 로직을 연결하기 전에 Android 클라이언트가 구현해야 할 기능을 사용자 흐름 순서로 식별한다. 각 단계에서 필요한 MVI `Intent`/`Effect`와 API를 함께 적는다.

범위는 2단계 면접 준비부터 질문·답변 반복, 종료, 영상 업로드, 중단 후 재개, STT 실패, 종료 결과 인계까지다. 리포트 조회·대기·렌더링과 AI의 질문 생성·채점 규칙 자체는 이번 브랜치의 Android 구현 범위에 포함하지 않는다.

0~1단계는 완료된 선행 작업으로 전제한다. 디바이스에 `sessionId`가 있으면 클라이언트 관점에서 진행 중인 면접이 있는 것으로 판단하고, 없으면 진행 중인 면접이 없는 것으로 판단한다. 이 로컬 존재 판정은 서버가 해당 면접을 재개할 수 있다고 판단하는 것과 별개다. 재개 가능 여부는 저장된 `sessionId`로 `A5`를 호출해 확인한다. 홈의 Part 1 세션 생성 로직은 이 문서의 구현 대상이 아니다.

참고 자료:

- `prd.md`의 면접 진행, 타이밍, 종료, STT 실패 규칙
- `api-docs.json`의 `Interview`, `Interview Resume` 및 흐름상 필요한 영상·리포트 API
- `memo.txt`의 세션 재개와 영상 세그먼트 보존 논의
- 현재 `InterviewRoute`, `InterviewContract`, `InterviewErrorContract`와 관련 ViewModel/UI
- `docs/CONSTITUTION.md`, `docs/ARCHITECTURE.md` 및 MVI·Navigation·오류 처리 계약

표기:

- **기존**: 현재 Contract에 이미 존재한다.
- **확장**: 현재 항목을 유지하되 실제 책임을 보강한다.
- **신설**: 현재 Contract에 없어서 추가가 필요하다.
- **결정**: 대안 검토가 끝나 구현 기준으로 확정되었다.
- **보류**: 제품·API·데이터 정책 결정 없이는 구현하면 안 된다.

## 2. 전체 흐름 요약

아래 번호는 흐름 순서이며 4장의 단계 번호와 1:1로 대응하지 않는다. 대응 단계를 괄호에 적는다.

1. 카메라·마이크를 점검하면서 서버의 질문 준비 상태를 폴링한다. (2단계)
2. 사용자가 시작 버튼을 누르면 전체 영상 녹화와 면접 타이머를 시작하고 첫 질문을 음성으로 재생한다. (3단계)
3. 질문 재생, 답변 녹음, 답변 제출, 다음 질문 재생을 세션 종료까지 반복한다. (4~6단계)
4. 경과 시간에 따라 종료 가능 상태, 랩업 플래그, 최종 카운트다운을 처리한다. (7단계)
5. 자연 종료·수동 종료·최대 시간 종료·중도 이탈에 맞는 종료 요청을 보낸다. (8단계)
6. 종료 응답이 리포트 생성을 알리면 녹화 세그먼트를 합치고 영상을 직접 업로드한다. (9단계)
7. 네트워크 단절이나 앱 재접속 시 로컬 `sessionId`로 진행 중 면접의 존재를 확인하고, 서버 조회 결과로 재개 가능 여부를 판단해 이어서 진행하거나 중단한다. (10~11단계)
8. STT 실패로 세션이 무효화되면 재개·중단 API를 호출하지 않고 실패 안내 후 세션을 정리한다. (12단계)
9. 종료와 영상 업로드 작업을 안전하게 인계한 뒤 app 계층에 면접 종료 결과를 전달한다. 이후 홈·리포트 이동은 이번 브랜치 범위 밖이다. (13단계)

## 3. API 식별자

아래 식별자를 단계별 API 목록에서 사용한다.

| ID | API | 용도 |
|---|---|---|
| `A2` | `GET /api/v1/interview/sessions/{sessionId}/status` | 즉시 한 번 호출한 뒤 5초 간격으로 질문 준비 상태 조회. `PROCESSING`, `READY`, `FAILED`. |
| `A3` | `GET /api/v1/interview/sessions/{sessionId}/questions/{questionId}/audio/stream` | 질문 TTS를 `audio/mpeg` 청크 스트림으로 재생. |
| `A4` | `POST /api/v1/interview/sessions/{sessionId}/answers` | `m4a` 답변과 타임라인을 제출하고 다음 질문 또는 종료 결과 수신. |
| `A5` | `GET /api/v1/interview/sessions/{sessionId}/resume` | 로컬에 저장된 진행 중 면접이 서버에서 재개 가능한지 `RESUMABLE` 또는 `ENDED`로 조회. |
| `A6` | `POST /api/v1/interview/sessions/{sessionId}/resume` | 사용자가 이어서 진행하기를 선택했을 때 재개 확정 및 최신 질문 수신. |
| `A7` | `POST /api/v1/interview/sessions/{sessionId}/abandon` | 재접속 화면과 `SERVER_TEMPORARY` 화면에서 중단 확정. `NETWORK_DISCONNECT` 또는 `USER_EXIT` 전송. |
| `A8` | `POST /api/v1/interview/sessions/{sessionId}/video/upload-url` | 영상용 S3 presigned PUT URL 발급. |
| `A9` | `PUT {uploadUrl}` | 클라이언트가 S3로 `video/mp4`를 직접 업로드. `Content-Type`은 `A8` 응답값과 같아야 한다. |
| `A10` | `POST /api/v1/interview/sessions/{sessionId}/video/complete` | 영상 업로드 완료 확정. 필요하면 마무리 멘트 재생 구간도 전달. |

`A1`에 해당하는 세션 생성은 완료된 선행 흐름이 소유하므로 식별자를 부여하지 않고 단계별 API 목록에서도 사용하지 않는다.

현재 `InterviewApi`, `InterviewRepository`와 UseCase에는 `A2`~`A8`, `A10`의 대부분이 이미 있다. 그러나 면접 ViewModel은 이를 전혀 호출하지 않는다. `A9` 직접 업로더와 실제 녹화·재생 엔진은 없다. 리포트 조회 API는 이번 브랜치에서 연결하지 않는다.

## 4. 단계별 사용자 플로우

### 2단계. 면접 준비: 권한·장치·질문 준비

사용자 경험:

- 전면 카메라 프리뷰를 보며 카메라, 마이크, 질문 준비의 세 항목을 순서대로 확인한다.
- 권한은 카메라 후 마이크 순서로 요청한다.
- 마이크는 권한 승인과 녹음기 초기화까지만 확인하고 음량을 검사하지 않는다.
- 서버 준비가 `FAILED`이면 자동 재시도가 끝난 상태로 보고, 이용권 미차감 안내와 처음으로 버튼을 표시한다.

Android에서 벌어져야 할 일:

- 완료된 선행 흐름이 저장한 `sessionId`를 읽는다. 값이 없다면 면접 화면을 시작하지 않고 선행 흐름의 상태 복구 대상으로 돌려보낸다.
- `sessionId`와 함께 저장된 `retentionDeadlineEpochMillis`를 확인한다. 기한이 지났다면 API 호출이나 미디어 접근 전에 만료 정리를 수행하고 면접 화면을 시작하지 않는다.
- `CAMERA`, `RECORD_AUDIO` 권한을 확인하고 필요한 경우 순서대로 요청한다.
- 전면 카메라 프리뷰가 실제 프레임을 공급하는지 확인한다. 얼굴 인식은 하지 않는다.
- 영상의 AAC 오디오 트랙에서 답변 구간을 `m4a`로 내보낼 수 있도록 녹화·Media3 파이프라인을 초기화한다.
- 장치 점검과 별개로 `A2`를 즉시 한 번 호출하고, terminal 상태가 올 때까지 5초 간격으로 폴링한다. 화면을 떠나거나 terminal 상태가 오면 폴링을 취소한다.
- 카메라·마이크·서버 준비와 여유 저장 공간 확인이 모두 성공해야 시작 버튼을 활성화한다. 480p 영상 목표 비트레이트 2 Mbps를 기준으로 12분 원본과 병합 결과 합계에 20% 안전 여유를 더해 최소 약 450 MB를 요구한다. 기기가 2 Mbps를 정확히 지원하지 않으면 480p를 유지하면서 2 Mbps 이하에서 가장 가까운 지원 비트레이트를 사용한다.
- 실제 사용자 영상·음성·질문 데이터와 파일 경로를 로그, 분석 도구, 크래시 리포트에 남기지 않는다.

Intent/Effect:

- `InterviewIntent.CheckCameraPermission` — **기존**, 카메라·마이크 권한 점검으로 확장.
- `InterviewIntent.LoadInterview` — **신설**, 선행 흐름에서 저장한 세션 로드와 `A2` 폴링 시작.
- `InterviewIntent.ClickRetryDeviceCheck` — **신설**, 카메라·마이크 점검 재시도.
- `InterviewIntent.ClickOpenSettings` — **신설**, 영구 거부된 권한의 앱 설정 열기 요청.
- `InterviewIntent.ReportCameraBindingFailure` — **기존**, 카메라 점검 실패 반영.
- `InterviewIntent.ReportCameraReady` — **신설**.
- `InterviewIntent.ReportMicrophoneReady` / `ReportMicrophoneFailure` — **신설**.
- `A2` 결과는 ViewModel의 비공개 처리 함수가 State로 반영한다. 네트워크 응답 자체를 Intent로 다시 만들지 않는다.
- `InterviewEffect.CameraBindingFailed` — **기존**, 장치 실패 UI에 연결.
- `InterviewEffect.RequestCameraPermission` / `RequestMicrophonePermission` — **신설**.
- `InterviewEffect.OpenAppSettings` — **신설**.
- `InterviewEffect.QuestionPreparationFailed` — **신설**, app이 준비 실패 결과를 해석.

API 목록:

- `A2` 반복 호출.

### 3단계. 시작 안내와 사용자의 면접 시작 확정

사용자 경험:

- 준비가 끝나면 “질문은 소리로만 나온다”, “면접은 약 10분 진행된다”만 안내한다.
- 사용자가 면접 시작하기를 눌러야 시작한다. 자동 시작하지 않는다.

Android에서 벌어져야 할 일:

- 시작 탭을 중복 처리하지 않는다.
- 전체 면접 영상 녹화를 시작하고 첫 질문 재생을 준비한다.
- 시작 탭 시 클라이언트 타이머를 생성하고 로컬에 저장한다. 타이머는 화면 recomposition 횟수가 아니라 클라이언트 기준 시각과 현재 시각 차이로 계산하며, 실제 앱 백그라운드 진입 시 진행 시간을 동결한다.
- 서버가 반환하는 `startedAt`, `elapsedSeconds`와 이후 추가될 수 있는 시간 보정값은 타이머 계산에 사용하지 않는다. 저장된 클라이언트 타이머만 경과 시간의 단일 원본이다.
- 첫 질문은 화면 텍스트로 노출하지 않는다.
- 첫 질문의 `questionId`와 질문 재생 구간을 세션 영상 타임라인 기준으로 기록한다.

Intent/Effect:

- `InterviewIntent.StartInterview` — **기존**, 녹화·타이머·첫 질문 시작까지 책임 확장.
- `InterviewEffect.StartSessionRecording` — **신설**.
- `InterviewEffect.PlayQuestionAudio` — **신설**, Screen의 Media3/ExoPlayer 계층이 실행.

API 목록:

- 첫 질문을 포함한 모든 질문에 `A3` 호출. `A2.summaryQuestion.ttsAudio`는 사용하지 않는다.

### 4단계. 질문 음성 재생

사용자 경험:

- 질문 재생 중에는 “질문 듣는 중” 상태를 표시한다.
- 질문 텍스트와 다시 듣기 버튼은 표시하지 않는다.
- 사용자가 답변으로 “다시 말씀해주세요”라고 요청한 경우 서버가 돌려준 질문을 다시 재생한다.

Android에서 벌어져야 할 일:

- `A3`를 progressive playback으로 재생한다. 전체 다운로드 완료를 기다리지 않는다.
- 재생 시작·종료 시각을 녹화 타임라인 초 단위로 보관한다.
- 재생이 끝난 뒤에만 답변 녹음 상태로 전환한다.
- 스트림 중간 단절은 HTTP 상태 코드가 아니라 플레이어 오류 callback으로 감지한다. 한 번 자동 재시도하고, 다시 실패하면 사용자 재시도 UI를 표시한다. 재시도 중에도 클라이언트 타이머는 계속 흐른다.
- 화면에는 질문 문자열을 저장하거나 렌더링하지 않는다.

Intent/Effect:

- `InterviewIntent.ReportQuestionPlaybackStarted` — **신설**.
- `InterviewIntent.ReportQuestionPlaybackCompleted` — **신설**.
- `InterviewIntent.ReportQuestionPlaybackFailure` — **신설**.
- `InterviewIntent.ClickRetryQuestionAudio` — **신설**.
- `InterviewEffect.PlayQuestionAudio` — 3단계에서 신설한 Effect를 질문마다 재사용한다.
- `InterviewEffect.ShowQuestionAudioRetry` — **신설**, 화면별 일회성 오류.
- `InterviewIntent.ChangeSpeaker`는 제거한다. 실제 플레이어·녹음 callback Intent로 발언 상태를 결정한다.

API 목록:

- `A3` 호출. 플레이어 중단 시 같은 식별자로 재호출.

### 5단계. 답변 녹음과 답변 종료 확정

사용자 경험:

- 질문 재생이 끝나면 “답변 녹음 중”을 표시한다.
- 사용자가 실제로 말을 시작한 뒤에만 답변 끝내기 버튼을 표시한다.
- 발화 뒤 10초 침묵 또는 사용자의 답변 끝내기 탭으로 답변을 확정한다.
- 아무 말 없이 질문을 건너뛰는 UI는 제공하지 않는다.

Android에서 벌어져야 할 일:

- 질문·답변 경계에서 영상 세그먼트를 확정하고, 질문마다 답변 구간의 AAC 오디오를 별도 `m4a`로 추출한다. 독립된 답변 녹음 스트림을 중복 운영하지 않는다.
- 음성 시작과 침묵을 감지한다. 발화 전 사고 시간에는 제한을 두지 않고, 첫 발화 이후 10초 침묵하면 답변을 자동 확정한다. 10초 기준은 기능 상수로 분리한다.
- 답변 시작·종료·길이를 녹화 타임라인 초 단위로 기록한다.
- 확정 후 상태를 “답변을 정리하고 있어요”로 바꾸고 되돌리지 않는다.
- 제출 결과가 확정되기 전까지 답변 파일을 보존한다. 네트워크 timeout은 서버 수신 여부가 불명확하므로 즉시 삭제하지 않는다.

Intent/Effect:

- `InterviewIntent.ReportAnswerSpeechStarted` — **신설**.
- `InterviewIntent.ReportAnswerSilenceElapsed` — **신설**.
- `InterviewIntent.ClickFinishAnswer` — **신설**.
- `InterviewIntent.ReportAnswerRecordingCompleted` — **신설**, 불투명 파일 참조와 타임라인 메타데이터 전달.
- `InterviewEffect.StartAnswerRecording` / `StopAnswerRecording` — **신설**, 매니저에 답변 구간 시작·종료와 세그먼트 확정·`m4a` 추출을 요청한다.
- `InterviewEffect.SubmitRecordedAnswer`는 만들지 않고, API 호출은 ViewModel이 `SubmitAnswerUseCase`로 수행한다.

API 목록:

- 답변 확정 시 `A4` 호출.
- 정상 진행은 `endType=null`, `audio=m4a`, `isWrapUp=(경과 시간 >= 8분 45초)`로 보낸다.
- `questionAudioStartAt`, `questionAudioEndAt`, `answerStartAt`, `answerEndAt`, `answerDuration`도 함께 보낸다.
- `SKIP`은 API에 존재하지만 PRD가 무발화 건너뛰기를 금지하므로 사용자 정상 흐름에서는 호출하지 않는다.

### 6단계. 답변 응답 처리와 다음 턴 반복

Android에서 벌어져야 할 일:

- `sessionEnded=false`이면 `nextQuestion.questionId`를 현재 질문으로 교체하고 4단계로 돌아간다.
- `nextQuestion.isLast=true`여도 사용자에게 내부 랩업 상태를 노출하지 않는다.
- API는 503 `AI_TEMPORARILY_UNAVAILABLE`에서 아직 아무것도 저장되지 않았음을 보장한다. 첫 503에서는 같은 `sessionId`, `questionId`, 답변 파일과 메타데이터로 `A4`를 즉시 한 번 자동 재시도한다. 다시 503이면 파일과 제출 체크포인트를 보존한 채 `InterviewErrorRoute(SERVER_TEMPORARY)`로 전환하고, `InterviewErrorScreen`에서 사용자가 누를 때마다 같은 요청을 수동 재시도한다. 수동 재시도 횟수는 별도로 제한하지 않으며 공통 24시간 보존 기한은 연장하지 않는다.
- 자동·수동 재시도 중에도 클라이언트 타이머는 계속 흐른다. 12분 hard cap이 발생하면 현재 답변의 동일 요청 재시도를 먼저 직렬로 완료하고, 응답이 세션을 끝내지 않은 경우에만 대기 중인 `A4(endType=HARD_CAP)`를 다음 요청으로 즉시 보낸다. 안전한 재시도 요청의 `endType`이나 파일·메타데이터를 중간에 바꾸지 않는다.
- 일반 네트워크 timeout과 409 `ANSWER_ALREADY_SUBMITTED`에서는 답변 파일을 보존하고 `A5`로 서버 상태를 조회한다. 사용자가 재개를 확정하면 `A6`으로 최신 질문을 회복한다.

Intent/Effect:

- `InterviewEffect.AnswerSubmissionRetryRequired` — **신설**, 두 번째 503 뒤 app이 `InterviewErrorRoute(SERVER_TEMPORARY)`를 조립하도록 전달한다.
- `InterviewErrorIntent.ClickRetryAnswerSubmission` — **신설**, `SERVER_TEMPORARY`에서 보존된 동일 요청을 다시 호출한다.
- `InterviewErrorEffect.AnswerSubmissionRecovered` — **신설**, 재시도 성공이 세션을 끝내지 않았을 때 app이 면접 화면으로 복귀시키도록 전달한다.
- `A4` 성공 결과는 ViewModel의 비공개 처리 함수가 State로 반영한다. 네트워크 응답 자체를 Intent로 다시 만들지 않는다.
- `InterviewEffect.PlayQuestionAudio` — 다음 질문마다 반복.
- `InterviewEffect.AnswerSubmissionNeedsRecovery` — **신설**, timeout 또는 이미 제출된 답변의 복구 진입.

API 목록:

- `A4` 반복 호출.
- 다음 질문마다 `A3` 호출.
- 수신 여부가 불명확한 실패는 `A5`, 사용자 확정 후 `A6` 호출.

### 7단계. 시간 기반 UI와 종료 가능 상태

Android에서 벌어져야 할 일:

- 타이머는 0:00부터 증가하는 경과 시간을 표시한다. 현재 `InterviewTimer`의 남은 시간 표시와 반대다.
- 8분에 5초 Toast를 한 번만 띄우고 종료 버튼을 활성화한다.
- 8분 이후 잔여 시간 인디케이터를 표시한다.
- 8분 45초부터 답변 제출의 `isWrapUp=true`를 사용한다. 화면에는 랩업 용어를 노출하지 않는다.
- 11분 50초부터 화면·음성 10초 카운트다운을 시작하되 “12분”이라는 숫자는 노출하지 않는다.
- 12분에 종료 의도를 한 번 큐에 넣는다. 진행 중 `A4`와 종료 요청은 하나의 직렬 상태 머신에서 처리하며, 서버가 이미 반환한 `STT_RESET` 또는 종료 결과를 추가 종료 요청보다 우선한다.

Intent/Effect:

- `InterviewIntent.UpdateElapsedTime` — **신설**, lifecycle ticker가 전달하거나 ViewModel이 기준 시각으로 계산.
- `InterviewIntent.ReportHardCapReached` — **신설**.
- `InterviewEffect.ShowEarlyFinishAvailable` — **신설**, 8분 Toast.
- `InterviewEffect.PlayFinalCountdown` — **신설**.
- `InterviewState.elapsedSeconds` — **기존**, 저장된 클라이언트 타이머만으로 갱신. 서버 시간 필드는 반영하지 않는다.
- `InterviewState.remainingSeconds`는 보조 계산으로만 유지 가능하다. 기본 타이머 UI 입력은 `elapsedSeconds`여야 한다.

API 목록:

- 시간 경과만으로 호출하는 API는 없다.
- 12분 도달 시 8단계의 `A4(endType=HARD_CAP)` 호출.

### 8단계. 정상·수동·강제·중도 이탈 종료

#### 8.1 자연 종료

- 마지막 답변을 `A4(endType=null)`로 제출한다.
- `sessionEnded=true`, `endType=NORMAL_END`이면 마무리 멘트를 재생하고 응답의 `reportGenerating`에 따라 영상 종료·업로드 단계로 간다.

#### 8.2 8분 이후 수동 종료

- `InterviewIntent.ClickFinishInterview` — **기존**.
- `InterviewEffect.FinishRequested` — **기존**, 종료 확인 Modal 요청까지 연결.
- 확인 시 `InterviewIntent.ConfirmFinishInterview` — **신설**.
- 현재 질문 식별자로 `A4(endType=MANUAL_END)`를 호출한다. 요청의 오디오 필드는 선택이지만 손상되지 않은 부분 답변 오디오가 있으면 함께 보낸다.
- 성공하면 화면 전환을 먼저 시작하고 짧은 마무리 멘트를 재생한다.

#### 8.3 최대 시간 강제 종료

- `InterviewIntent.ReportHardCapReached`를 한 번만 처리한다.
- `A4(endType=HARD_CAP)`를 호출한다. 요청의 오디오 필드는 선택이지만 손상되지 않은 부분 답변 오디오가 있으면 함께 보낸다.
- 응답의 마무리 멘트를 재생하고 다음 단계로 간다.

#### 8.4 8분 전 사용자 이탈

- Back/X 입력 시 차감 경고를 표시한다. 이 시점에는 리포트 생성 여부를 약속하지 않는다.
- `InterviewIntent.ClickPermissionDeniedBack`은 권한 거부 준비 화면 전용으로 유지한다.
- 진행 중 Back/X에는 `InterviewIntent.ClickExitInterview`, `ConfirmEarlyExit`, `DismissEarlyExit` — **신설**.
- 확인 시 `A4(endType=BACK_EXIT)`를 호출한다. 요청의 오디오 필드는 선택이지만 손상되지 않은 부분 답변 오디오가 있으면 함께 보낸다.
- `wrapUpMessage`는 없고, 종료 응답의 `reportGenerating`에 따라 영상 처리 단계로 간다.

공통 Intent/Effect:

- `InterviewEffect.ShowFinishConfirmation` — **신설**.
- `InterviewEffect.ShowEarlyExitWarning` — **신설**.
- `InterviewEffect.PlayWrapUpMessage` — **신설**.
- `InterviewEffect.InterviewEnded` — **신설**, app 계층이 면접 종료 결과로 해석한다. 실제 목적지는 이번 브랜치 범위 밖이다.

API 목록:

- 이 단계의 서버 종료 확정은 모두 `A4`를 사용한다. 재접속 화면과 `SERVER_TEMPORARY` 화면의 중단 확정만 `A7`을 사용한다.
- 종료 요청이 답변 녹음과 겹치면 손상되지 않은 부분 답변 오디오를 포함한다. 모든 `A4`와 종료 의도는 중복 제출을 막는 단일 직렬 상태 머신으로 처리한다.
- 영상 병합·업로드 단계 진입은 종료 응답의 `reportGenerating`을 따른다. `reportGenerating=false`이면 9단계 업로드를 시작하지 않고 로컬 미디어를 공통 24시간 정리 대상으로 남긴다.
- 준비 중이거나 첫 질문 시작 전 이탈은 로컬 진행 상태만 삭제한다. 서버의 보류 만료·환불 처리에 의존하며, 서버 정리 API 부재와 상태 불일치 위험 및 향후 교체 지점을 구현 KDoc에 명시한다.

### 9단계. 영상 확정, 병합, 업로드

Android에서 벌어져야 할 일:

- 면접 시작부터 종료까지의 전면 카메라 영상을 `mp4` 세그먼트로 확보한다. 중단 뒤 재개할 때 기존 파일을 덮어쓰지 않고 다음 순번의 새 세그먼트를 만든다.
- 백그라운드·네트워크·마이크 문제나 프로세스 종료로 여러 파일이 생기면 파일 수정 시간이 아니라 로컬 세그먼트 순번과 클라이언트 타임라인 순서로 병합한다.
- 질문·답변 경계마다 영상 세그먼트를 확정하고, 답변 구간의 오디오를 질문별 `m4a`로 추출한다. 같은 질문의 답변 음성이 여러 조각이면 해당 `questionId`의 조각만 하나로 병합해 `A4`로 전송하며 서로 다른 질문의 음성을 합치지 않는다.
- 종료 시 확정되지 않은 마지막 임시 조각은 제외한다. 확정 세그먼트가 손상되면 업로드를 중단하고 원본을 최대 24시간 보존한다.
- 녹화는 480p, 목표 비트레이트 2 Mbps, AVC/H.264, AAC, 30fps로 고정하고 회전 메타데이터를 정규화한다. 기기가 정확한 비트레이트를 지원하지 않으면 2 Mbps 이하에서 가장 가까운 값을 선택하고 실제 선택값을 manifest에 기록한다. Media3 Transformer `Composition`으로 질문별 `m4a`와 최종 `mp4`를 정규화·내보낸다.
- 질문·답변 경계의 세그먼트 교체 공백은 500ms 이하를 목표로 한다. 500ms를 초과하면 실제 공백 길이를 manifest에 기록하되 면접과 이후 병합·업로드는 계속한다.
- 마무리 멘트를 재생했다면 녹화 타임라인 기준 `wrapUpStartSec`, `wrapUpEndSec`를 기록한다.
- 화면을 떠난 뒤 병합·업로드는 `feature:interview:impl`의 면접 전용 `InterviewVideoUploadWorker`가 무작위 `uploadTaskId`를 고유 작업 이름·tag·`inputData`로 사용하는 OneTime WorkManager 작업으로 소유한다. Worker는 domain UseCase만 호출하고, `data`의 API·S3 PUT·파일 저장 구현에 직접 접근하지 않는다. 자동 업로드의 기본 네트워크 제약은 `UNMETERED`이며, 모바일 데이터에서는 사용자 확인을 받은 작업만 `CONNECTED` 제약으로 실행한다. Network Error는 지수 backoff로 한 작업당 최대 3회 자동 재시도한다. 최신 API 계약이 `A8` URL 재발급, 같은 고정 키에 대한 `A9` 재업로드, 멱등인 `A10` 반복 호출을 안전한 복구로 명시하므로 이 세 작업의 Server Error도 같은 정책으로 최대 3회 재시도한다. 모두 소진하면 `task.json`을 `FAILED_RETRYABLE`로 저장하고, 다음 앱 시작·포그라운드에서 기한이 남고 동일 `uploadTaskId`의 실행·대기 작업이 없을 때 새 작업 묶음을 한 번 enqueue한다. 그 밖의 Server Error와 Unknown Error는 `pendingGlobalErrorType`으로 보존해 다음 app foreground에서 Global Event로 전달한다.
- 장시간 Worker는 면접 내용·세션 식별자·파일 경로가 없는 진행 알림을 표시한다.
- `sessionId` 최초 로컬 저장 시점에 계산한 보존 기한 24시간이 되면 업로드 대기 또는 실패 여부와 무관하게 WorkManager를 취소하고 영상·음성·변환 결과와 진행 상태를 삭제한다.
- 기한 OneTime WorkManager를 예약하고 앱 시작·포그라운드·면접 진입 때도 만료 여부를 확인한다. 정확한 Alarm 권한은 추가하지 않으며 OS 지연으로 백그라운드 삭제가 늦어질 수 있지만, 앱이 다시 활성화되면 만료 데이터 접근 전에 정리한다.
- 면접 종료 직전 기존 `FINISHING` UI에 “영상은 기본적으로 Wi-Fi 연결 시 업로드되며, 면접 세션 생성 후 24시간 안에 업로드되지 않으면 자동으로 삭제돼요.”라는 안내 텍스트를 추가한다. `RequestMeteredUploadConfirmation`이 요청하는 모바일 데이터 업로드 확인 UI만 예외로 허용하고, 그 밖의 새 화면·별도 디자인 흐름은 만들지 않는다.
- `A8`의 URL 만료 시 새 URL을 발급받는다.
- `A9`가 실제 성공한 뒤에만 `A10`을 호출한다.
- `A4` 성공 시 해당 답변 오디오를 즉시 삭제하고, `A10` 성공 시 영상 원본과 병합 결과를 삭제한다. 실패 파일은 최대 24시간만 보존하며 로그아웃·회원 탈퇴 시 즉시 삭제한다. 현재 제품에 없는 계정 변경은 고려하지 않는다.
- 업로드 재시도 중 민감한 URL, 영상 경로, 사용자 데이터를 로그에 남기지 않는다.

Intent/Effect:

- `InterviewIntent.ReportRecordingSegmentFinalized` — **신설**.
- `InterviewIntent.ReportAnswerAudioFragmentFinalized` — **신설**.
- `InterviewIntent.ReportVideoMergeCompleted` / `ReportVideoMergeFailure` — **신설**.
- `InterviewEffect.FinalizeSessionRecording` — **신설**.
- `InterviewEffect.MergePendingAnswerAudio` — **신설**, 같은 `questionId`의 조각만 병합.
- `InterviewEffect.EnqueueVideoUpload` — **신설**. Screen의 Feature Android 실행 계층이 같은 모듈의 `InterviewVideoUploadWorker`를 `uploadTaskId` 고유 작업으로 enqueue한다.
- `InterviewEffect.RequestMeteredUploadConfirmation` — **신설**, 모바일 데이터 업로드 확인 UI 요청.
- `InterviewIntent.ConfirmMeteredUpload` / `DismissMeteredUpload` — **신설**. 승인 시 `CONNECTED`, 거절 시 `UNMETERED` 제약으로 작업을 인계한다.
- `InterviewIntent.ReportVideoUploadEnqueued` / `ReportVideoUploadEnqueueFailure` — **신설**, `EnqueueVideoUpload` 실행 결과를 ViewModel State로 되돌린다.
- 24시간 삭제 안내는 지속 UI 정보이므로 별도 Effect를 만들지 않고 `FINISHING` State가 렌더링한다.

API 목록:

- `A8` 호출.
- `A9` 직접 PUT.
- `A10` 호출.

### 10단계. 네트워크 중단과 연결 복구

사용자 경험:

- 연결이 끊기면 현재 진행을 안전하게 멈추고 네트워크 오류 안내를 표시한다.
- 연결 복구 후 재개 가능하면 이어서 진행하기와 중단하기를 제공한다.
- 이어서 진행하기를 누르면 서버가 준 최신 질문부터 계속한다.

Android에서 벌어져야 할 일:

- 재생·녹음·제출 중 어느 지점에서 끊겼는지와 무관하게 현재 영상 세그먼트를 안전하게 확정한다.
- 현재 `sessionId`, 클라이언트 타이머, `questionId`, 미확정 답변 파일, 타임라인, 기존 영상 세그먼트를 보존한다.
- 연결이 돌아오면 `A5`로 서버 상태를 다시 확인한다.
- `A5`의 `startedAt`, `elapsedSeconds`는 무시하고 `resumeState`와 `status`만 재개 판정에 사용한다.
- 로컬 타이머가 12분 미만이어도 `A5.resumeState=ENDED`이면 서버의 terminal 상태를 우선해 세션 생명주기를 끝낸다. hold 만료는 서버가 `ABANDONED`로 정리한 결과에 포함된다. 로컬 타이머는 시간 UI와 로컬 이력에만 사용한다.
- `RESUMABLE`일 때 사용자 선택 전에는 `A6`을 호출하지 않는다.
- 이어서 진행하기 탭 후 `A6`을 호출하고 `sessionEnded=false`이면 `nextQuestion`을 `A3`로 재생한다.
- `A6`이 200이면서 `sessionEnded=true`, `abandonCause=HOLD_EXPIRED`이면 재개하지 않고 세션을 정리한 뒤 종료 결과를 app에 전달한다. 실제 목적지는 app이 정한다.
- 중단하기 탭 후 `A7(cause=NETWORK_DISCONNECT)`을 호출한다. 409 `SESSION_ALREADY_ENDED`는 중복 성공으로 취급한다.

Intent/Effect:

- `InterviewIntent.ReportNetworkDisconnected` / `ReportNetworkRestored` — **신설**.
- `InterviewEffect.InterviewConnectionInterrupted` — **신설**, 체크포인트 저장 완료 뒤 Screen이 app callback으로 전달한다.
- `InterviewErrorIntent.ClickResume` — **기존**, 실제 `A6` 호출로 확장.
- `InterviewErrorIntent.ClickAbort` — **기존**, NETWORK일 때 `A7(NETWORK_DISCONNECT)` 호출로 확장.
- `InterviewErrorEffect.ResumeInterview` — **기존이지만 이름 변경 권장**: `InterviewResumeConfirmed`.
- `InterviewErrorEffect.NavigateToHome` — **기존이지만 이름 변경 권장**: `InterviewAbandonCompleted`. 실제 목적지는 app이 정한다.
- 면접 준비·질문 재생·답변 제출·재개·미디어 업로드 중 이 문서에 복구 동작이 명시된 Network Error는 Constitution의 Interview 예외에 따라 Feature State/Effect 또는 지속 작업 상태로 처리한다.
- 진행 중 면접 연결 단절은 로컬 미디어와 세션 체크포인트를 안전하게 저장한 뒤 app에 `InterviewConnectionInterrupted` 결과를 전달한다. app은 기존 `InterviewErrorRoute(NETWORK)`를 조립하고, `InterviewErrorScreen`이 `A5` 확인과 재개·중단 UI를 소유한다.
- 복구 동작이 명시되지 않았거나 체크포인트 저장을 보장할 수 없는 Interview Network Error, Interview 밖의 Network Error는 Global Event와 app 종료 Modal을 사용한다. Interview Server Error 또는 서버 측 스트림 실패는 API가 중복 부수 효과 없는 안전한 재시도를 명시하고 이 문서가 복구 동작을 정의한 `A3` 재호출, `A4` 503과 `A8`~`A10` 업로드 작업만 Feature State/Effect 또는 지속 작업 상태로 처리한다. 그 밖의 Server Error와 모든 Unknown Error는 Global Event로 처리한다.

API 목록:

- 복구 시 `A5`.
- 이어서 진행하기 탭 시 `A6`, 이후 `A3`.
- 중단하기 탭 시 `A7(cause=NETWORK_DISCONNECT)`.

### 11단계. 앱 백그라운드·프로세스 종료 후 재접속

Android에서 벌어져야 할 일:

- 질문 재생·답변 녹화·제출 중 앱이 백그라운드로 가면 재생과 타이머를 멈춘다. 열린 녹화 세그먼트의 마감을 manifest에 반영하고 진행 상태를 동결한 뒤 홈으로 이동한다. 저장 실패는 기존 로컬 오류 경로로 처리한다.
- 1:00에 동결하고 1분 뒤 돌아오면 홈 카드와 재개 시점 모두 1:00이다. 보존 기한은 최초 저장 후 24시간이며 동결 중에도 계속 줄어든다.
- 준비 중이면 준비 화면을 유지한다. 서버가 이미 종료를 확정한 랩업 중에는 재생을 끝내고 기존 마감 절차를 계속한다. 구성 변경과 앱 내부 화면 이동은 동결을 일으키지 않는다.
- 홈은 저장된 동결 상태를 발견하면 `A5`로 확인하고 재개 가능한 면접 카드를 표시한다. ‘이어서 진행’을 누르면 `A6`으로 받은 최신 질문과 로컬 경과 시간으로 기기를 준비하고 새 세그먼트를 시작한다.
- ‘처음부터 시작’의 재확인을 수락하면 기존 중단 절차로 `A7(cause=USER_EXIT)`을 보낸다. 동결만으로 답변을 재제출하거나 세션을 중단하거나 업로드하지 않는다.
- 프로세스 종료 뒤에도 동결 표식과 경과값을 복원한다. 동결 표식이 없는 기존 진행 데이터는 종전의 단조 시계와 epoch 체크포인트로 복원한다.

API 목록:

- 재접속 또는 포그라운드 복귀 시 `A5`.
- 사용자 재개 확정 시 `A6`.
- 사용자 중단 확정 시 `A7(cause=USER_EXIT)`.

### 12단계. STT 실패와 세션 무효화

발생 경로:

- `A4` 응답이 `sessionEnded=true`, `endType=STT_RESET`.
- `A5` 응답이 `resumeState=ENDED`, `status=INVALID`.

Android에서 벌어져야 할 일:

- 진행 중 재생·녹음을 중지하고 STT 실패 화면을 표시한다.
- 이용권이 차감되지 않는다는 안내를 표시한다.
- 이미 서버에서 무효화가 끝났으므로 `A6`과 `A7`을 호출하지 않는다.
- 사용자가 확인하면 세션을 정리하고 STT 무효화 결과를 app에 전달한다. 실제 목적지는 app이 정한다.
- 카메라·마이크 권한·장치 오류는 `MIC_DEVICE`, 서버의 누적 STT 실패는 `STT`로 분리한다.
- Android는 별도의 “이해 불가·범위 밖 발화 5회” 규칙을 세거나 표시하지 않는다. API에 명시된 서버 누적 STT 실패율 초과로 `STT_RESET`을 받은 경우만 같은 무효화 흐름으로 처리한다.

Intent/Effect:

- `InterviewEffect.InterviewInvalidatedByStt` — **신설**.
- `A4`/`A5`의 STT 무효화 결과는 ViewModel의 비공개 처리 함수가 State와 Effect로 반영한다.
- `InterviewErrorIntent.ClickAbort` — **기존**, MIC/STT 화면에서는 API 없이 확인 동작으로 처리.
- `InterviewErrorEffect.SttFailureAcknowledged` — **신설**, 기존 `NavigateToHome` 대체 권장.

API 목록:

- 새 호출 없음. 원인이 된 `A4` 또는 이전의 `A5` 결과만 사용한다.

### 13단계. 면접 종료 결과 인계

Android에서 벌어져야 할 일:

- 진행 중 면접의 `sessionId`를 지우기 전에 영상 업로드 작업이 별도 작업 정보로 세션 식별자와 로컬 파일 소유권을 안전하게 넘겨받았는지 확인한다.
- 인계 후에는 업로드용 작업 정보와 무관하게 진행 중 면접 판정용 `sessionId`와 타이머를 제거한다.
- 면접 종료 결과를 app 계층에 전달한다. app이 실제 목적지를 조립하며, 리포트 조회·대기·렌더링은 이번 브랜치에서 구현하지 않는다.

Intent/Effect:

- `InterviewEffect.InterviewEnded` — **신설**, 종료 유형과 업로드 인계 여부를 app에 전달한다.

API 목록:

- 이 단계에서 새로 호출하는 API는 없다. 영상 처리의 `A8`, `A9`, `A10`은 9단계 작업이 소유한다.

## 5. 현재 Contract 대비 핵심 변경 목록

### `InterviewContract`

현재 유지 가능한 항목:

- `CheckCameraPermission`
- `StartInterview`
- `ReportCameraBindingFailure`
- `ClickPermissionDeniedBack`
- `ClickFinishInterview`
- `CameraBindingFailed`
- `PermissionDeniedExitRequested`
- `FinishRequested`

교체 또는 보강이 필요한 항목:

- `ChangeSpeaker`는 제거하고 질문 재생·답변 녹음 callback Intent로 상태를 결정한다.
- `InterviewScreenState`: 준비/진행 여부만으로는 부족하다. 최소 `DEVICE_CHECK`, `QUESTION_PREPARING`, `START_GUIDE`, `QUESTION_PLAYING`, `ANSWER_RECORDING`, `ANSWER_SUBMITTING`, `FINISHING`을 구분해야 한다.
- `InterviewState`의 `var`는 immutable State 계약에 맞게 `val`로 바꾼다.
- `speaker`만으로는 “답변을 정리하고 있어요”를 표현할 수 없다. 별도 진행 단계가 필요하다.
- 현재 UI는 남은 시간을 표시하지만 PRD는 경과 시간을 표시한다.
- `sessionId`, `questionId`, 준비 상태, 권한·장치 상태, 제출 상태, 클라이언트 타이머, 종료 가능 여부, 영상 업로드 인계 상태가 추가로 필요하다.
- ViewModel은 미디어 실행을 Effect로 요청하고 Screen의 Android 계층이 `InterviewMediaSessionManager`를 호출한다. 매니저와 Worker enqueue를 포함해 Screen이 요청한 Android 작업 결과는 `Report...` Intent로 `onIntent()` 하나에 되돌린다. callback Intent에는 Android Framework·CameraX·Media3·WorkManager 객체나 실제 미디어 바이트를 넣지 않고 상태 전이에 필요한 최소 결과와 불투명 파일 참조만 전달한다. 외부 소비자가 없는 `InterviewStarted` Effect는 만들지 않는다.

### `InterviewErrorContract`

현재 Intent는 버튼과 잘 맞지만 Effect가 목적지 이름을 직접 표현한다.

- `NavigateToHome`은 `InterviewAbandonCompleted` 또는 `SttFailureAcknowledged`로 바꾼다.
- `ResumeInterview`는 `InterviewResumeConfirmed`로 바꾼다.
- 실제 route 결정은 app 계층이 담당한다.
- `InterviewErrorType`의 최종 집합은 `MIC_DEVICE`, `NETWORK`, `STT`, `SERVER_TEMPORARY` 네 종류다. 기존 `MIC`는 `MIC_DEVICE`로 이름을 바꾸고, 카메라·마이크 권한 또는 장치 초기화 실패는 `MIC_DEVICE`, 진행 중 연결 단절은 `NETWORK`, `A4.endType=STT_RESET` 또는 `A5.status=INVALID`는 `STT`, 동일 `A4`의 두 번째 503은 `SERVER_TEMPORARY`로 매핑한다. 그 밖의 Network/Server/Unknown 오류를 이 enum에 추가하지 않고 공통 오류 계약을 따른다.
- `InterviewErrorScreen`은 enum을 `when`으로 빠짐없이 분기한다. `MIC_DEVICE`와 `STT`는 API를 추가 호출하지 않는 단일 확인 버튼, `NETWORK`는 `A5` 확인 뒤 재개·중단 버튼, `SERVER_TEMPORARY`는 동일 `A4` 재시도·`A7(USER_EXIT)` 중단 버튼을 사용한다. `InterviewErrorState`에는 오류 유형별 `isLoading`, `canResume`, `canRetryAnswerSubmission`, `failureMessage` 같은 API 처리 상태가 필요하다.
- 기존 `InterviewErrorScreen`을 진행 중 면접의 네트워크 복구와 `A4` 임시 서버 오류 복구 화면으로 확장한다. `InterviewErrorState`가 미디어 확정, `A5` 확인, 재개 가능, 답변 재제출 중, 중단 중 상태를 지속 UI State로 표현하고, 실제 `sessionId`·타이머·manifest와 미제출 답변은 로컬 저장소에서 복원한다.
- `SERVER_TEMPORARY`의 재시도 성공 응답이 세션을 끝내지 않았으면 다음 `questionId`를 체크포인트에 저장한 뒤 `InterviewErrorEffect.AnswerSubmissionRecovered`를 발행한다. app은 이를 현재 면접으로 복귀시키고 `A3` 재생부터 이어간다. 성공 응답이 세션을 끝냈으면 일반 면접 종료 결과를 발행한다.
- `SERVER_TEMPORARY`는 기존 dual-button 구조를 유지한다. 재시도 버튼은 보존된 동일 `A4`를 호출하고, 중단 버튼은 `A7(cause=USER_EXIT)`을 호출한다. 화면에는 “중단하면 이용권이 차감돼요”처럼 차감 사실만 안내하고 리포트 생성 여부는 약속하지 않는다. 성공 응답의 `ticketOutcome=COMMITTED`, `reportGenerating=true`를 그대로 반영해 확보한 전체 영상을 병합·업로드하며, 리포트 생성 성공 시 차감 확정·실패 시 환급되는 서버 정책을 클라이언트가 임의로 보정하지 않는다. 중복 요청의 409 `SESSION_ALREADY_ENDED`는 중단 성공으로 처리한다.

### 현재 연결·미디어 구현 공백

- `InterviewViewModel`에는 UseCase 주입이 없고 현재 Intent는 화면 상태 토글만 수행한다.
- `InterviewErrorViewModel`은 `A6`/`A7`을 호출하지 않고 Effect만 즉시 발행한다.
- `InterviewNavigationModule`의 `onNavigateHome`, `onResumeInterview` callback은 모두 빈 함수다.
- `InterviewCameraPreview`는 CameraX `Preview`만 bind하고 `VideoCapture`나 세션 녹화를 하지 않는다.
- `feature:interview:impl`에는 Media3/ExoPlayer, 영상 녹화·병합, 지속 업로드 구현이 없다.
- `A3`는 Bearer 인증이 필요하다. 단순 URL을 ExoPlayer에 넘기는 것만으로는 부족하며 인증 헤더를 붙이는 전용 `DataSource.Factory`가 필요하다.
- `A4`는 `m4a`만 허용하지만 현재 repository는 multipart media type을 `audio/*`로 만든다. 서버가 요구하는 정확한 media type과 파일 확장자를 맞춰야 한다.
- `A9`는 presigned URL에 앱 API의 Bearer 인증을 붙이지 않는 전용 PUT client가 필요하다. `A8.contentType`을 그대로 보내야 한다.
- 현재 질문 진행 정보, 영상 세그먼트, 미확정 답변, 업로드 작업을 복원하는 로컬 저장소가 없다.

## 6. 로컬 상태·미디어 저장 설계와 구현 소유권

### 6.1 진행 중 면접 존재와 재개 가능성 — 결정 완료

- **진행 중 면접 존재의 단일 원본은 디바이스의 `sessionId`다.** `sessionId`가 없으면 `A5`를 호출하지 않고 진행 중 면접이 없는 것으로 처리한다.
- `sessionId`가 있으면 클라이언트에는 진행 중 면접이 있다. 다만 오래되거나 서버에서 이미 종료된 세션일 수 있으므로 이것만으로 재개 버튼을 노출하지 않는다.
- 준비 화면에서는 `sessionId`가 있고 타이머가 아직 없을 수 있다. 이 상태도 진행 중 면접으로 판정하되, 타이머는 사용자가 시작 버튼을 누를 때만 생성한다.
- **재개 가능성의 단일 원본은 `A5`다.** `RESUMABLE`이면 재개 선택을 제공하고, `ENDED`이면 재개할 수 없는 상태로 정리한다.
- `A5`가 네트워크 오류로 실패하면 로컬 `sessionId`를 지우지 않는다. 이때는 “진행 중 면접 있음, 재개 가능성 미확인” 상태로 보존하고 `InterviewErrorScreen`의 재시도 가능한 State로 유지한다. 체크포인트가 없거나 해당 오류에 문서화된 복구 경로가 없으면 Global Event로 전환한다.
- 서버가 종료 상태를 반환했거나 면접이 정상 종료되면 진행 중 판정용 `sessionId`를 제거한다. 영상 업로드가 남았다면 업로드 작업이 별도의 `sessionId` 사본과 미디어 소유권을 먼저 인계받아야 한다.

### 6.2 클라이언트 타이머 SSoT — 결정 완료

서버 응답의 `startedAt`, `elapsedSeconds`는 전송 계약을 위해 파싱할 수 있지만 타이머, 8분 종료 허용, 8분 45초 랩업, 11분 50초 카운트다운, 12분 강제 종료 계산에는 사용하지 않는다.

**결정: 단조 시계 + 체크포인트 혼합.** `timerStartedAtEpochMillis`, `elapsedAtCheckpointMillis`, `checkpointedAtEpochMillis`를 저장하고, 프로세스가 살아 있는 동안에는 메모리의 단조 시계를 사용한다. 동결 상태에서는 저장된 경과값을 표시하고, 재개 시 그 값에서 새 기준점을 잡는다. 실행 중이던 타이머는 마지막 체크포인트와 클라이언트 시각 차이로 복원한다. 타이머는 절대로 감소시키지 않고 0~12분으로 제한한다. 시각이 크게 앞으로 이동했거나 재부팅 뒤 계산값이 12분 이상이면 즉시 hard cap에 진입한다. 서버 시간으로 보정하지 않는다.

### 6.3 저장해야 할 가벼운 정보 — 결정 완료

`sessionId`와 타이머 외에도 재개·재시도·병합을 위해 다음 메타데이터가 필요하다. 질문 본문, TTS 원본, 실제 음성·영상 바이트는 가벼운 상태 저장소에 넣지 않는다.

| 정보 | 필요한 이유 | 확정 저장 위치 |
|---|---|---|
| `sessionId` | 진행 중 면접 존재 판정과 API 호출 | Preferences DataStore |
| `uploadTaskId` | WorkManager에서 실제 세션·파일 정보를 분리해 업로드 작업 식별 | WorkManager 이름·tag·`inputData`와 전용 업로드 작업 저장소 |
| `retentionDeadlineEpochMillis` | `sessionId` 최초 로컬 저장 시점부터 24시간인 공통 삭제 기한 | Preferences DataStore |
| 보존 기한 단조 시계 체크포인트 | 실행 중 시스템 시각 역행으로 24시간 기한이 연장되는 것 방지 | Preferences DataStore |
| 클라이언트 타이머 시작값·체크포인트 | 프로세스 종료와 백그라운드 뒤 타이머 복원 | Preferences DataStore |
| 현재 `questionId`와 미확정 답변 타임라인 | 같은 질문의 음성 조각 병합과 `A4` 재시도 | 미디어 manifest |
| 영상·답변 음성 세그먼트의 순번, 종류, 상대 경로, 시작·종료 시간, 확정 상태 | 손실 없는 복원과 순서 기반 병합 | 미디어 manifest |
| 랩업 재생 구간 | `A10` 요청 복원 | 미디어 manifest |
| 병합·업로드·완료 확정 상태 | `A9` 성공 후 `A10` 호출 보장과 중복 작업 복원 | 미디어 manifest와 전용 업로드 작업 저장소 |

**결정: 전용 Preferences DataStore.** `data`에 면접 진행 상태 전용 저장소를 만들고 `sessionId`, `retentionDeadlineEpochMillis`, 보존 기한 단조 시계 체크포인트, 타이머 필드를 원자적으로 갱신한다. `retentionDeadlineEpochMillis`는 선행 단계가 `sessionId`를 로컬에 처음 저장할 때 현재 클라이언트 시각+24시간으로 한 번만 기록하고 재개·오류·업로드 재시도로 연장하지 않는다. 기존 공용 `preferences` 파일에 섞기보다 `noBackupFilesDir` 아래 별도 DataStore 파일을 사용해 기기 이전·클라우드 백업으로 오래된 진행 상태가 복원되지 않게 한다. 구조화된 세그먼트 목록은 Preferences에 문자열 목록으로 넣지 않고 별도 미디어 목록 파일(manifest)로 관리한다. Android 공식 지침도 [DataStore를 소량 데이터에 적합한 저장소](https://developer.android.com/topic/libraries/architecture/datastore)로 설명하며, [`noBackupFilesDir`을 자동 백업 대상에서 제외](https://developer.android.com/identity/data/autobackup)한다.

실행 중에는 저장된 남은 보존 시간과 `elapsedRealtime` 차이를 함께 사용하고 epoch 계산 결과와 비교해 더 짧은 남은 시간을 채택한다. checkpoint 이후 시스템 시각이 뒤로 이동해도 남은 시간을 늘리지 않는다. 재부팅으로 단조 시계 기준이 초기화된 경우에는 저장된 epoch deadline을 사용한다.

기존 설치에 `sessionId`는 있지만 deadline이 없으면 manifest의 기록 시각, 미디어 파일 생성 시각, 면접 DataStore 파일 시각 중 유효한 가장 이른 값을 기준으로 deadline을 복원한다. 미래 시각이나 읽을 수 없는 값은 제외하고, 유효한 시각이 하나도 없으면 이관 시점의 현재 클라이언트 시각을 사용한다. 계산된 deadline이 이미 지났으면 즉시 만료 정리를 수행하며 파일 경로나 시각은 일반 로그에 남기지 않는다.

면접 시작 시 타이머 필드를 같은 `edit`에서 기록하고, 종료·무효화·중단 정리가 끝나면 진행 중 판정용 `sessionId`와 타이머 필드를 같은 `edit`에서 제거한다. 업로드 인계 시 암호학적으로 예측하기 어려운 무작위 `uploadTaskId`를 생성하고 WorkManager의 고유 이름·tag·`inputData`에는 이 값만 넣는다. 인계가 완료된 작업은 진행 중 상태와 분리된 `noBackupFilesDir/interview/uploads/{uploadTaskId}/`가 `task.json`, manifest와 모든 미디어를 함께 소유하며, Worker는 `uploadTaskId`를 받는 domain UseCase로 조회한다.

`task.json`은 임시 파일에 완전한 JSON을 쓴 뒤 rename하는 방식으로 원자 교체한다. 현재 `schemaVersion`은 1이며 최소 필드는 `uploadTaskId`, `sessionId`, manifest 상대 위치, `status`, 현재 작업 묶음의 재시도 횟수, nullable `pendingGlobalErrorType`과 nullable `pendingGlobalEventId`다. `status`는 최소 `PENDING_MERGE`, `PENDING_UPLOAD`, `PENDING_COMPLETE`, `FAILED_RETRYABLE`, `FAILED_GLOBAL`을 구분한다. presigned URL, 절대 파일 경로, 실제 미디어 바이트와 오류 원문은 저장하지 않는다. Worker나 foreground 정리가 `task.json`의 손상·누락 또는 지원하지 않는 schema를 확인하면 복구를 시도하지 않는다. `uploadTaskId`의 고유 WorkManager 작업을 취소하고 no-backup 업로드 작업 디렉터리와 같은 ID의 캐시 작업 디렉터리를 즉시 모두 삭제한다.

`uploadTaskId`도 일반 로그·분석·크래시 리포트에 기록하지 않는다. `A10` 성공, 공통 24시간 만료, 로그아웃·회원 탈퇴 정리 때 WorkManager 작업, `task.json` 매핑과 해당 작업 디렉터리를 함께 삭제한다.

미디어 manifest는 세션 디렉터리마다 원자적으로 교체하는 JSON 파일 하나로 구현한다. 임시 파일에 완전한 JSON을 쓴 뒤 rename하여 부분 쓰기를 노출하지 않는다.

### 6.4 영상·음성 파일 저장 위치 — 결정 완료

**결정: 혼합 전략.** 면접 진행·재개 중인 영상 세그먼트, 미제출 답변 음성, manifest는 `noBackupFilesDir/interview/{sessionId}`에 둔다. 면접 종료 후 업로드를 인계할 때에는 이 세션 디렉터리에 `task.json`을 원자 저장한 뒤 디렉터리 전체를 같은 볼륨의 `noBackupFilesDir/interview/uploads/{uploadTaskId}/`로 rename하고, 두 작업이 모두 성공한 뒤에만 Worker를 enqueue한다. 실패하면 Worker를 enqueue하지 않고 남아 있는 디렉터리를 공통 24시간 정리 대상으로 유지한다. 원본에서 다시 만들 수 있는 병합·변환 중간 산출물만 `cacheDir/interview/uploads/{uploadTaskId}/`에 둘 수 있다. 최종 업로드 파일도 업로드가 끝나기 전까지는 캐시 삭제로 유실되면 안 되므로, 원본에서 즉시 재생성할 수 있다는 보장이 없으면 no-backup 업로드 작업 디렉터리에 둔다. Android는 [저장 공간이 부족하면 캐시 파일을 삭제할 수 있으므로](https://developer.android.com/training/data-storage/app-specific) `cacheDir`을 재개 원본의 단일 저장소로 사용하면 안 된다.

앱 수준의 별도 미디어 암호화는 추가하지 않는다. 앱 전용 저장소 접근 제한과 기기의 파일 기반 암호화를 사용하고 보존 시간을 짧게 제한한다. `A4` 성공 시 해당 답변 오디오를, `A10` 성공 시 영상 원본과 병합 결과를 즉시 삭제한다. 실패 파일의 최대 보존 시간은 24시간이며 로그아웃·회원 탈퇴 시 즉시 삭제한다. 현재 제품에 없는 계정 변경은 고려하지 않는다. 실제 미디어, 경로, presigned URL은 로그·분석·크래시 리포트에 기록하지 않는다.

로그아웃·회원 탈퇴 정리는 `ClearInterviewLocalDataUseCase` 하나로 제공한다. 이 UseCase는 업로드 WorkManager 취소와 로컬 데이터 삭제를 각각 domain 계약으로 호출한다. `feature:interview:impl`의 작업 제어 구현이 `uploadTaskId` tag로 면접 Worker를 취소하고, `data` 구현이 진행 상태 DataStore, 업로드 작업 매핑, manifest, 영상·음성·변환 결과를 정리한다. 기존 `LogoutUseCase`와 `WithdrawUserUseCase`가 이 UseCase를 조합해 계정 동작별 순서와 부분 실패를 소유하며, `MyPageViewModel`은 합성된 계정 UseCase 하나만 호출한다. Feature끼리 직접 의존하거나 개별 저장소를 순서대로 호출하는 우회 구현은 사용하지 않는다.

기본 호출 순서는 로그아웃의 경우 원격 로그아웃 시도→면접 로컬 정리→인증 세션 정리, 회원 탈퇴의 경우 서버 탈퇴 성공→면접 로컬 정리→인증 세션 정리다. 면접 로컬 정리가 실패해도 인증 세션 삭제와 계정 완료 화면 이동은 계속한다. 민감 값이 없는 `isInterviewCleanupPending` 표시를 `noBackupFilesDir`의 별도 최소 Cleanup Preferences DataStore에 저장하고 즉시 한 번 재시도하며, 남아 있으면 다음 앱 시작 시 화면 진입 전에 다시 정리한다. 정리가 성공한 뒤에만 표시를 제거한다. 이 저장소에는 세션 ID, 파일 경로, 오류 원문을 넣지 않는다.

### 6.5 다중 세그먼트 처리 구조 — 결정 완료

**결정: 세션 오케스트레이터와 업로드 Worker의 책임 분리.** `feature:interview:impl`의 `InterviewMediaSessionManager`는 화면이 살아 있는 동안의 녹화·답변 음성 순서와 상태 전이만 소유하고, 같은 모듈의 `InterviewVideoUploadWorker`는 화면이 사라진 뒤 최종 영상 병합과 업로드 순서를 소유한다. 두 실행자는 다음 최소 구성 요소를 조합한다.

- 녹화기: 현재 영상 세그먼트를 시작·중지하고 확정 결과를 반환한다. 답변 `m4a`는 확정 영상의 AAC 트랙에서 추출한다.
- 로컬 미디어 저장소: 다음 세그먼트 순번 발급, manifest 원자적 갱신, 파일 존재·손상 여부 확인을 담당한다.
- 병합기: 확정된 입력만 순번대로 연결한다. 매니저는 질문별 답변 음성을, Worker는 최종 영상을 만든다.
- 업로드 UseCase: `A8`~`A10`을 실행하며 Worker가 API 구현체를 직접 호출하지 않게 한다.

질문·답변 경계마다 영상 세그먼트를 확정하고 답변 구간 오디오를 질문별 `m4a`로 추출한다. Media3 Transformer `Composition`을 사용해 입력을 정규화하고 최종 영상과 답변 음성을 내보낸다. 녹화 형식은 480p, 목표 비트레이트 2 Mbps, AVC/H.264, AAC, 30fps다. 2 Mbps를 정확히 지원하지 않으면 480p를 유지하면서 그 이하에서 가장 가까운 지원값을 선택하고 manifest에 기록한다. 면접 시작 전 최소 여유 공간은 12분 원본과 병합 결과 합계에 20%를 더한 약 450 MB다.

복구·병합 규칙:

- 세그먼트 이름이나 파일 수정 시간이 아니라 manifest의 증가 순번을 사용한다.
- 중단 시 현재 조각을 확정하고, 재개 시 새 순번으로 시작한다.
- 질문·답변 경계의 세그먼트 교체 공백은 500ms 이하를 목표로 하며, 초과한 공백 길이는 manifest에 기록하고 면접을 계속한다.
- 확정되지 않은 마지막 조각은 병합에서 제외한다. 확정 세그먼트가 손상되면 업로드를 중단하고 원본을 최대 24시간 보존한다.
- 같은 `questionId`의 미제출 음성 조각만 하나로 합친다. 이미 `A4` 성공이 확인된 답변이나 다른 질문의 음성을 다시 합치지 않는다.
- 영상은 모든 확정 세그먼트를 하나의 `mp4`로 합친 뒤에만 `A9`로 보낸다.
- 병합 결과는 임시 경로에 만든 뒤 성공 시 최종 경로로 원자적으로 교체한다.
- `A9` 성공 뒤에만 `A10`을 호출하며, `A10` 성공 전에는 필요한 원본의 소유권을 해제하지 않는다.
- `feature:interview:impl`의 `InterviewVideoUploadWorker`가 병합·업로드를 `uploadTaskId` 고유 OneTime WorkManager 작업으로 실행한다. Worker는 domain UseCase와 Feature 내부 Media3 병합기만 사용한다. 자동 작업은 `UNMETERED`를 요구하고, 사용자가 모바일 데이터 사용을 승인한 작업만 `CONNECTED`를 요구한다. Network Error와 안전한 반복이 명시된 `A8`~`A10` Server Error는 지수 backoff로 한 작업당 최대 3회 재시도한다. 모두 실패하면 `FAILED_RETRYABLE`로 원자 저장하고 Worker를 실패 종료한다.
- 앱 시작·포그라운드 진입 시 만료 정리를 먼저 수행한 뒤 `FAILED_RETRYABLE` 작업을 조회한다. 공통 24시간 기한이 남고 같은 `uploadTaskId`의 WorkInfo가 `ENQUEUED`, `RUNNING` 또는 `BLOCKED`가 아닐 때만 `ExistingWorkPolicy.KEEP`으로 새 작업 묶음을 한 번 enqueue한다. 현재 foreground 동안 같은 작업을 다시 만들지 않고 공통 기한도 연장하지 않는다.
- 복구 계약이 없는 Server Error 또는 Unknown Error가 발생하면 Worker는 `status=FAILED_GLOBAL`, `pendingGlobalErrorType=SERVER|UNKNOWN`을 `task.json`에 원자 저장하고 실패 종료한다. 다음 앱 시작·포그라운드에서 domain UseCase는 먼저 기존 `pendingGlobalEventId` 묶음이 있으면 같은 ID와 오류 유형을 다시 발행한다. 열린 묶음이 없을 때만 ID가 없는 작업을 오류 유형별로 조회하고, Server Error 작업이 있으면 그 시점의 Server 작업 전체를, 없으면 Unknown Error 작업 전체를 하나의 스냅샷으로 묶는다. 불투명한 무작위 `pendingGlobalEventId`를 각 `task.json`에 기록한 뒤 같은 ID와 기존 Global Event를 담은 `GlobalAppEventEnvelope`를 한 번 발행하며, 새로 발생한 오류나 다른 유형의 오류는 이 스냅샷에 포함하지 않는다.
- app-level renderer는 Global Event가 실제 표시 State에 반영된 뒤 `pendingGlobalEventId`로 acknowledgment UseCase를 호출한다. UseCase는 같은 ID를 가진 작업을 다시 조회해 WorkManager 작업을 취소하고 해당 업로드 작업 디렉터리와 복구 불가능한 미디어를 모두 삭제한다. acknowledgment 전에 프로세스가 종료되면 다음 foreground에서 같은 ID와 오류 유형을 다시 발행한다. Server Error 묶음을 우선하며 남은 Unknown Error 묶음은 이후 foreground에서 발행한다. ID, HTTP body, 오류 원문, 세션 ID와 파일 경로는 오류 표시에 전달하거나 로그로 남기지 않는다.
- 장시간 실행 시 민감 정보 없는 진행 알림을 표시한다.
- `sessionId` 최초 로컬 저장 시점에 계산한 공통 보존 기한 24시간이 되면 업로드 대기·실패 상태를 포함해 관련 WorkManager를 취소하고 모든 미디어와 진행 상태를 삭제한다. 기한 OneTime WorkManager와 앱 시작·포그라운드·면접 진입 시 만료 검사를 함께 사용하며, 정확한 Alarm 권한은 추가하지 않는다. OS가 백그라운드 작업을 지연할 수 있지만 앱이 다시 활성화되면 만료 데이터에 접근하기 전에 정리한다.

### 6.6 구현 소유권

| 책임 | 소유 모듈/계층 |
|---|---|
| 화면 상태, 사용자 Intent, 미디어 작업 요청 Effect | `feature:interview:impl` |
| API 호출 계약, DTO, 원격 데이터 소스 | `data` |
| 세션·답변·재개·영상 업로드 UseCase | `domain` |
| `sessionId`와 클라이언트 타이머 Preferences DataStore | domain repository 계약 + `data` 구현 |
| `uploadTaskId`와 실제 세션·manifest·미디어·업로드 체크포인트 매핑 | domain repository 계약 + `data`의 작업별 no-backup 디렉터리와 원자적 `task.json` 구현 |
| `FAILED_RETRYABLE` 조회와 foreground 재enqueue | domain 조회 계약 + `feature:interview:impl` 작업 제어 구현; app은 foreground 진입점만 조립 |
| Worker의 미처리 Global Error 분류·전달 묶음 저장과 acknowledgment 정리 | domain repository 계약 + `data`의 `task.json` 구현; domain UseCase가 유형별 스냅샷 생성·조회·삭제를 조율 |
| 미디어 manifest와 파일 생명주기 메타데이터 저장 | domain repository 계약 + `data` 구현 |
| CameraX, Media3/ExoPlayer, 녹음·질문별 음성 병합 실행 | `feature:interview:impl`의 UI와 분리된 `InterviewMediaSessionManager` 계층 |
| Navigation 3 route 조립과 실제 화면 이동 | `app` |
| 복구 계약이 없는 Network/Server 오류와 모든 Unknown 오류의 전역 표시 | `core:common` event + `app` host |
| 지연 Global Event 표시 State 반영 뒤 acknowledgment UseCase 호출 | `app` host; Repository나 Feature 저장소에 직접 접근하지 않음 |
| S3 직접 PUT과 업로드 API 전송 구현 | `data` |
| 화면 밖 최종 영상 병합·업로드 WorkManager 오케스트레이션 | `feature:interview:impl`의 `InterviewVideoUploadWorker`; domain UseCase와 Feature 내부 Media3 병합기를 조합 |
| 면접 Worker enqueue·취소 구현 | `feature:interview:impl`의 작업 제어 구현; domain 작업 제어 계약을 구현 |
| Hilt WorkerFactory와 WorkManager 앱 설정 조립 | `app`; 면접 작업 순서나 재시도 정책은 소유하지 않음 |
| 로그아웃·회원 탈퇴 시 면접 로컬 데이터 일괄 삭제 | `domain`의 `ClearInterviewLocalDataUseCase` + `data` 구현; `LogoutUseCase`·`WithdrawUserUseCase`가 조합 |
| 인증 삭제 뒤 남은 면접 정리 필요 표시 | `data`의 별도 no-backup Cleanup Preferences DataStore; 앱 시작 정리 진입점이 소비 |

ViewModel은 `InterviewRepository` 구현체나 `InterviewApi`를 직접 호출하지 않고 UseCase만 호출해야 한다. ViewModel과 Content는 파일 경로, CameraX, Media3 객체를 직접 소유하지 않는다.

### 6.7 API·도메인 매핑 정책

- API 설명·예시와 `components` schema가 충돌하면 현재 구현은 schema를 기준으로 한다. `PRELOAD_FAILED`처럼 설명에만 있는 값은 알려진 상태 분기에 추가하지 않고, 문제 발생 시 서버와 계약을 다시 조정한다.
- `ticketOutcome`도 schema의 값을 기준으로 파싱하며 설명의 비정형 값으로 보정하지 않는다.
- 서버 문자열은 알려진 값을 enum/sealed type으로 표현하고, 새 값은 `Unknown(rawValue)`로 보존한다. 미확정 값을 임의의 종료·환불 상태로 치환하지 않는다.
- `Unknown(rawValue)`를 받으면 로컬 `sessionId`와 미디어를 보존하고 Global Unknown 오류를 표시한다. 포그라운드마다 `A5`를 다시 호출하되, `sessionId` 최초 로컬 저장 시점에 계산한 공통 보존 기한 24시간이 지나면 미디어와 `sessionId`를 모두 제거해 새 면접을 허용한다. 알려지지 않은 원문 값과 민감한 응답 payload는 로그에 남기지 않는다.
- `SummaryQuestion.turn`은 data/domain 매핑에서 유실하지 않는다.
- 서버의 `elapsedSeconds`는 전송 계층에서 `Long`으로만 파싱하고 클라이언트 타이머 State에는 전달하지 않는다.
- 서버의 terminal 상태가 세션 생명주기를 결정하고, 로컬 타이머는 시간 기반 UI·종료 의도 생성만 소유한다.

## 7. 권장 구현 순서

1. 전역 오류 계약을 먼저 정렬한다. `docs/architecture/error-handling.md`의 계약을 승인된 Envelope 방식으로 갱신한 뒤 `core/common/event/GlobalAppEvent.kt`에 `GlobalAppEventEnvelope`를 추가하고 `GlobalErrorHandler.kt`의 `SharedFlow`와 `emit(event, deliveryId = null)`을 변경한다. `app/error/GlobalErrorHost.kt`는 event 렌더링과 표시 확인 callback만 담당하고, app이 callback을 `AcknowledgePendingInterviewUploadGlobalEventUseCase`에 연결한다. `GlobalModalRequest.kt`와 Global Modal 계약은 변경하지 않는다. `GlobalErrorHandlerTest`에는 일반 오류의 `deliveryId=null`, 저장 오류 ID 보존, 연속 event 전달을 검증한다.
2. `InterviewErrorType`을 `MIC_DEVICE`, `NETWORK`, `STT`, `SERVER_TEMPORARY`로 확정하고 `InterviewRoute`, `InterviewErrorContract`, `InterviewErrorScreen`, Preview와 ViewModel 분기를 함께 변경한다. `A4`/`A5`/장치 오류의 매핑과 오류 유형별 버튼·API 호출 유무를 ViewModel 단위 테스트로 검증한다.
3. 전용 Preferences DataStore와 진행 중 면접 상태 repository를 만들고 `sessionId` 존재 판정, 최초 저장 시점+24시간 공통 deadline, 기존 설치 이관, 보존 기한 단조 시계 체크포인트 및 감소하지 않는 클라이언트 타이머를 구현한다.
4. 알려진 API 문자열을 enum/sealed type으로 바꾸고 `Unknown(rawValue)`, `SummaryQuestion.turn`, 서버 시간 격리를 mapper에 반영한다.
5. 세션별 원자적 JSON manifest, `Unknown`의 24시간 후 전체 정리 정책과 `ClearInterviewLocalDataUseCase`를 포함한 로컬 저장소를 구현한다.
6. 준비 화면의 권한·장치·약 450 MB 여유 공간 점검과 즉시 시작하는 5초 `A2` 폴링을 연결한다.
7. `feature:interview:impl`에 `InterviewMediaSessionManager`, `InterviewVideoUploadWorker`와 domain 작업 제어 계약 구현을 만들고, Effect→Android 실행 결과는 `Report...` Intent로 `onIntent()`에 되돌린다.
8. 질문별 `A3` 스트리밍, 한 번 자동 재시도, 질문/답변 경계 녹화와 질문별 `m4a` 추출을 구현한다.
9. timeout/409 재개 복구와 503 안전 재시도 상태를 포함한 직렬 `A4` 턴·종료 상태 머신을 구현한다. 첫 503은 동일 요청으로 한 번 자동 재시도하고, 두 번째 503부터 `InterviewErrorScreen`의 `SERVER_TEMPORARY` 상태에서 수동 재시도한다.
10. 승인된 Interview Network Error 예외에 따라 로컬 `sessionId` + 매 포그라운드 `A5` + 사용자 확정 `A6` 기반 `InterviewErrorScreen` 복구와 `MIC_DEVICE`/서버 `STT_RESET` 분기를 구현한다.
11. `feature:interview:impl`의 `InterviewVideoUploadWorker`에 작업별 업로드 디렉터리와 원자적 `task.json`, 2 Mbps 이하 지원값을 사용하는 Media3 세그먼트 병합과 `UNMETERED` 기본·모바일 데이터 사용자 승인·지수 backoff 최대 3회·`FAILED_RETRYABLE` foreground 복구·유형별 지연 Global Event acknowledgment·장시간 진행 알림 정책을 적용한 고유 OneTime WorkManager 기반 `A8`~`A10` 업로드·삭제를 구현한다. 저장된 오류 묶음의 ID 발급·재전달·acknowledgment 삭제는 domain/data 단위 테스트와 app host 표시 테스트로 검증한다.
12. 면접 종료 결과를 app 계층에 인계하고 lifecycle/process death 및 중복 요청 회귀 테스트를 추가한다. 리포트 기능은 구현하지 않는다.

## 8. 최소 수락 조건

- `GlobalErrorHandler.events`는 `GlobalAppEventEnvelope`를 전달한다. 일반 오류는 `deliveryId=null`, 저장된 Worker 오류는 해당 `pendingGlobalEventId`를 유지하며, `GlobalModalRequest`에는 전달 ID나 업로드 정보가 추가되지 않는다.
- `GlobalErrorHost`가 Modal/Toast 표시를 app-level UI State에 반영하기 전에는 acknowledgment하지 않는다. 반영 뒤 app callback이 `AcknowledgePendingInterviewUploadGlobalEventUseCase`를 호출하고, acknowledgment 전 프로세스가 끝나면 같은 ID가 다음 foreground에서 다시 전달된다.
- `InterviewErrorType`은 `MIC_DEVICE`, `NETWORK`, `STT`, `SERVER_TEMPORARY` 네 종류만 사용한다. 장치 오류, `A4.endType=STT_RESET`, `A5.status=INVALID`, 진행 중 연결 단절과 동일 `A4`의 두 번째 503이 각각 정해진 유형과 UI/API 흐름으로 빠짐없이 매핑된다.
- 로컬 `sessionId` 존재 여부만으로 진행 중 면접의 존재를 판정하고, 재개 가능 여부는 `A5`로만 판정한다.
- `sessionId`를 처음 저장할 때 공통 `retentionDeadlineEpochMillis`를 현재 클라이언트 시각+24시간으로 한 번만 기록하고 이후 흐름에서 연장하지 않는다.
- 기존 `sessionId`에 deadline이 없으면 유효한 manifest·미디어·DataStore 시각 중 가장 이른 값을 사용하고, 근거가 없을 때만 이관 시점의 현재 시각을 사용한다. 이미 만료된 값은 즉시 정리한다.
- 실행 중에는 epoch deadline과 단조 시계로 계산한 남은 시간 중 더 짧은 값을 사용해 시스템 시각 역행으로 보존 기한이 늘어나지 않게 한다. 재부팅 뒤에는 epoch deadline으로 복원한다.
- `A5` 조회 실패 시 로컬 `sessionId`를 임의로 삭제하지 않는다.
- 준비 화면은 카메라·마이크·서버 준비와 약 450 MB 여유 저장 공간이 모두 확인돼야 시작할 수 있고, `A2`는 즉시 한 번 호출한 뒤 5초 간격으로 폴링한다.
- 질문 텍스트는 화면이나 로그에 노출되지 않고 음성만 재생된다.
- 첫 질문을 포함한 모든 질문은 `A3`로 재생하고 `A2.summaryQuestion.ttsAudio`는 무시한다. 스트림 실패 시 한 번 자동 재시도한 뒤 사용자 재시도 UI를 표시한다.
- 각 답변은 올바른 `questionId`, `m4a`, 타임라인과 함께 서버에 한 번만 반영된다. 503 재시도는 동일한 논리 제출의 같은 파일·메타데이터를 사용하며 새 답변으로 만들지 않는다.
- 서버의 `startedAt`, `elapsedSeconds`를 무시하고 감소하지 않는 클라이언트 타이머만으로 8분, 8분 45초, 11분 50초, 12분 동작을 한 번씩 실행한다. 재부팅·큰 시각 점프 뒤에도 0~12분 범위를 지킨다.
- 자연·수동·강제·중도 이탈 종료가 올바른 `endType`을 사용하고, 종료 요청과 겹친 손상되지 않은 부분 답변 오디오를 함께 보낸다.
- 영상 병합·업로드는 종료 응답의 `reportGenerating`이 참일 때만 시작한다.
- `A4`와 종료 요청은 직렬화되고, timeout/409에서는 파일을 보존한 채 `A5`와 사용자 확정 `A6`으로 회복한다.
- `A4`의 첫 503은 동일 요청으로 한 번 자동 재시도하고, 다시 503이면 `InterviewErrorScreen(SERVER_TEMPORARY)`에서 사용자가 같은 요청을 재시도한다. 재시도 중 12분이 되면 동일 요청 결과를 확정한 뒤 세션이 계속되는 경우에만 대기 중인 hard cap 요청을 보낸다.
- `SERVER_TEMPORARY`에서 중단하면 차감 경고 뒤 `A7(USER_EXIT)`을 호출하고, 응답의 이용권·리포트 상태를 그대로 따른다. 409 `SESSION_ALREADY_ENDED`는 중복 성공으로 처리한다.
- 네트워크 중단 후 재개하면 서버가 준 최신 질문부터 시작하고 로컬 타이머가 중단 시간만큼 진행돼 있다.
- 문서화된 복구 경로가 있는 Interview Network Error는 체크포인트 저장 뒤 `InterviewErrorScreen`, Feature State/Effect 또는 지속 작업 상태로 처리한다. 안전한 반복 호출이 명시된 `A3` 스트림 재호출, `A4` 503과 `A8`~`A10` Server Error도 정의된 Feature 또는 지속 작업 복구 상태로 처리하고, 복구 계약이 없는 Network/Server Error와 모든 Unknown Error는 Global Event로 처리한다.
- 동결 후 홈 진입 시 `A5`를 호출하고, 사용자가 이어서 진행하기를 누른 뒤에만 `A6`을 호출한다.
- `MIC_DEVICE`와 서버 `STT_RESET`을 분리하고 Android는 별도의 5회 규칙을 적용하지 않으며, STT 무효화에서는 재개·중단 API를 추가 호출하지 않는다.
- 재개 전후 480p/목표 2 Mbps/H.264/AAC/30fps 영상 세그먼트는 원자적 JSON manifest 순번대로 Media3에서 하나의 `mp4`로 합쳐지고, 같은 질문의 미제출 음성 조각은 하나의 `m4a`로 합쳐진다. 정확한 2 Mbps를 지원하지 않는 기기는 그 이하의 가장 가까운 값을 쓰고 manifest에 기록한다.
- 세그먼트 교체 공백은 500ms 이하를 목표로 하며, 초과하더라도 manifest에 기록하고 면접·병합·업로드를 계속한다.
- 재개에 필요한 원본은 `cacheDir`의 자동 삭제에 의존하지 않으며, `A9` 성공 후에만 `A10`을 호출한다.
- 자동 영상 업로드는 `UNMETERED`에서 실행하고, 모바일 데이터 업로드는 사용자 승인을 받은 경우에만 실행한다. Network Error와 안전한 반복이 명시된 `A8`~`A10` Server Error는 지수 backoff로 최대 3회 재시도하며 장시간 작업 알림에는 민감 정보를 넣지 않는다.
- 3회 재시도 소진 시 `FAILED_RETRYABLE`로 저장하고, 다음 앱 시작·포그라운드에서 기한이 남고 동일 작업이 실행·대기 중이 아닐 때 작업당 한 번만 새 묶음을 enqueue한다. 앱을 다시 열어도 24시간 기한은 연장하지 않는다.
- 복구 불가능한 Worker Server/Unknown Error는 `FAILED_GLOBAL`과 분류를 저장한다. 다음 앱 foreground에서는 Server 우선으로 한 오류 유형의 현재 작업 스냅샷에 불투명한 `pendingGlobalEventId`를 부여하고, 같은 ID의 기존 Global Event를 한 번 전달한다. 오류 payload·원문·세션·파일 정보는 표시하거나 로그로 남기지 않는다.
- app-level 표시 State 반영 뒤 같은 `pendingGlobalEventId`를 acknowledgment하면 그 스냅샷의 작업과 작업 디렉터리 전체를 삭제한다. 새 오류는 함께 삭제하지 않고, acknowledgment 전 프로세스가 종료되면 같은 묶음을 다음 foreground에서 다시 전달한다.
- 화면 밖 영상 병합과 업로드 Worker는 `feature:interview:impl`이 소유하고 domain UseCase만 호출한다. `data`는 파일·API·S3 PUT 구현만 소유하며 Feature나 Worker에 의존하지 않는다.
- WorkManager 작업 이름·tag·`inputData`에는 무작위 `uploadTaskId`만 저장한다. 실제 `sessionId`, manifest와 업로드 체크포인트는 no-backup 전용 저장소에서 `uploadTaskId`로 조회하며, presigned URL·파일 경로·미디어 바이트를 WorkManager 데이터에 넣지 않는다.
- 업로드 인계 뒤 `noBackupFilesDir/interview/uploads/{uploadTaskId}/`가 `task.json`, manifest와 모든 원본·최종 미디어를 함께 소유하고, 재생성 가능한 중간 파일만 `cacheDir/interview/uploads/{uploadTaskId}/`에 둔다. `task.json`이 손상·누락됐거나 schema를 지원하지 않으면 상태 복구 없이 해당 작업을 취소하고 두 작업 디렉터리를 즉시 모두 삭제한다. 정상 작업도 성공·24시간 만료·로그아웃·탈퇴 시 두 디렉터리까지 삭제한다.
- 면접 종료 직전 기존 `FINISHING` UI에 24시간 안에 업로드되지 않은 영상은 삭제된다는 텍스트를 표시한다. `sessionId` 최초 저장 기준 기한 도달 시 대기·실패 작업을 취소하고 미디어와 진행 상태를 삭제한다.
- 기한 OneTime WorkManager가 OS 정책으로 지연될 수 있으므로 앱 시작·포그라운드·면접 진입에서도 만료 데이터를 사용하기 전에 정리한다. 정확한 Alarm 권한은 요구하지 않는다.
- `A4` 성공 답변 음성과 `A10` 성공 영상은 즉시 삭제되고, 실패 파일은 24시간 이내에 삭제된다. 로그아웃·회원 탈퇴 시 `ClearInterviewLocalDataUseCase`가 업로드 작업과 모든 로컬 면접 데이터를 정리한다.
- 계정 완료 뒤 면접 정리가 실패해도 인증 세션 삭제와 화면 이동은 계속하며, 비식별 정리 필요 표시를 저장해 즉시 재시도하고 다음 앱 시작에서 다시 정리한다.
- 정리 필요 표시는 별도 no-backup Cleanup Preferences DataStore에 저장하고 정리 성공 시 제거한다. 세션 ID·파일 경로·오류 원문은 저장하지 않는다.
- `Unknown(rawValue)` 상태에서는 로컬 `sessionId`와 미디어를 보존하고 Global Unknown 오류를 표시하며 원문 값은 로그에 남기지 않는다. 24시간 보존 기한이 지나면 미디어와 `sessionId`를 함께 제거한다.
- 리포트 조회·대기·렌더링 코드는 이번 브랜치에 추가하지 않는다.
- 실제 영상·음성·STT·질문·파일 경로가 로그, 분석 도구, 크래시 리포트, Preview/Catalog에 포함되지 않는다.
