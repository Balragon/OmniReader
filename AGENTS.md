# Codex 작업 지침 (mdvault)

이 저장소는 Claude Code와 Codex가 함께 작업한다. **Claude 플랜 사용량 한도가
95%를 넘으면 사용자가 Codex로 전환한다** — 그 경우 이 문서가 진입점이다.
(이 문서의 이전 버전은 CLAUDE.md의 복사본이었으나, 규칙의 원본은 CLAUDE.md
하나로 유지한다 — 여기에 규칙을 복제하지 말 것.)

## 시작 절차 (반드시 순서대로)

1. **CLAUDE.md를 읽고 그 규칙 전부를 그대로 따른다**
   (아키텍처, 의존성 허용목록, 변환 파이프라인 순수성, 우선순위 규칙,
   테스트 정책 모두 동일하게 적용).
2. **docs/HANDOFF.md를 읽는다** — 현재 상태, 남은 작업, 지뢰 목록.
3. `git log --oneline -15`로 최근 작업 흐름을 파악한 뒤 시작한다.

## Codex 환경 참고

- 로컬 대화형 세션에서는 Gradle 실행 가능:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  ./gradlew test assembleRelease
  ```
  (omegacode 샌드박스에서만 데몬 소켓이 막혔던 것 — 로컬 터미널은 정상.)
- 에뮬레이터: `~/Library/Android/sdk/emulator/emulator -avd mdvault-api34`
  (headless: `-no-window -no-audio -no-boot-anim`).
  adb: `~/Library/Android/sdk/platform-tools/adb`.
- 실기기 Galaxy S21 Ultra 시리얼 `REDACTED` (연결 시).
  기기 복수 연결 시 `ANDROID_SERIAL`로 지정.
- mammoth는 Maven 원본이 아니라 **패치된 로컬 jar**
  (`app/libs/mammoth-1.9.0-android.jar`)를 쓴다 — 절대 catalog 의존성으로
  되돌리지 말 것. 사유·재생성 절차: `tools/mammoth-android-patch/README.md`.

## 필수 검증 규칙

- **release 관련 변경 후에는 반드시 에뮬레이터에서 release APK 스모크 테스트.**
  JVM 테스트로는 잡히지 않는 Android 전용 크래시가 이미 2건 있었다
  (R8×flexmark 병합, Android SAX). 목록: docs/HANDOFF.md "지뢰" 절.
- `connectedAndroidTest`는 종료 시 앱을 제거한다 — 이후 수동 테스트하려면
  release 재설치 + 볼트 재선택 필요.
- 검증 통과 전 커밋 금지.

## 작업 종료 시 의무 (기록 프로토콜)

다음 에이전트는 전체 코드를 다시 읽지 않는다 — `git log <마지막 커밋>..HEAD`
와 HANDOFF.md의 작업 로그 새 엔트리만 읽고 이어받는다. 그래서:

1. **커밋 메시지가 1차 기록이다**: 제목 한 줄(무엇) + 본문에 왜/어떻게
   검증했는지. diff로 알 수 있는 내용을 다른 문서에 반복하지 않는다.
2. `./gradlew test assembleRelease` 통과 확인 후 커밋. release 동작 관련
   변경이면 에뮬레이터 스모크까지.
3. **docs/HANDOFF.md "작업 로그" 맨 위에 엔트리 1개 append** (템플릿은 그
   문서에 있음). 특히 ⚠️ 항목 — 공용 시그니처/계약 변경, 새로 발견한 지뢰,
   미검증 사항 — 은 반드시 기록한다. 문서 전면 재작성 금지.
4. "현재 상태"/"다음 작업" 절은 내용이 실제로 달라졌을 때만 해당 줄을 수정.
5. `git push origin main`.
