# mdvault — 개인용 Android Markdown Vault

## 프로젝트 정의

개인용 Android Markdown vault 앱. LLM/DOCX 산출물을 Markdown으로 흡수하고,
필요할 때 제한된 DOCX를 새로 생성한다. **Markdown(.md)이 canonical 포맷**이며
DOCX는 import/export gateway다. **원본 DOCX는 절대 자동 덮어쓰지 않는다.**

## 1. 스택

- Kotlin, Jetpack Compose (Material 3)
- minSdk 29, targetSdk 34, compileSdk 34
- JDK 17 (Android Gradle Plugin 8.x 요구)
- 빌드: Gradle version catalog (`gradle/libs.versions.toml`) — 버전은 반드시 catalog에서만 정의

## 2. 아키텍처

- 단일 모듈 (`:app`), 패키지 경계 엄수:
  - `ui/` — 공통 UI, 네비게이션, 테마
  - `editor/` — Markdown 편집기 (EditorPort 인터페이스 뒤에 구현 격리)
  - `preview/` — Markdown 렌더링/미리보기
  - `document/` — 문서 도메인 모델, import/export 파이프라인 조립
  - `storage/` — SAF 기반 vault 접근 (DocumentsContract 직접 사용)
  - `markdown/` — flexmark 기반 변환 엔진 (순수 JVM)
  - `docx/` — DOCX import(Mammoth)/export(수제 OOXML writer) (순수 JVM)
  - `secondbrain/` — Second Brain 연동
  - `settings/` — 앱 설정 (DataStore Preferences)
- DI: 수동 DI (`AppContainer`). **Hilt 금지.**

## 3. 변환 파이프라인 격리 (빌드 강제)

`markdown/`, `docx/` 패키지는 Android API import **금지**:
`android.*`, `androidx.*` (Context, Uri, ContentResolver, BitmapFactory 포함 전부).
입출력은 `InputStream`/`OutputStream`/plain data class만 사용한다.

이 규칙은 `app/src/test/.../ConversionPurityTest.kt`가 소스를 스캔하여 강제한다
(위반 시 테스트 실패 = 빌드 실패). 이 테스트를 약화하거나 삭제하지 않는다.

## 4. 의존성 정책

허용 목록 (이외 추가는 **사전 승인 필요**):
- `com.vladsch.flexmark:flexmark` (core), `flexmark-html2md-converter`,
  `flexmark-ext-tables`, `flexmark-ext-gfm-tasklist`, `flexmark-ext-yaml-front-matter`
- `org.zwobble.mammoth:mammoth` (java-mammoth)
- `org.jsoup:jsoup`
- AndroidX 기본 (core-ktx, activity-compose, lifecycle, Compose BOM, DataStore Preferences)

**금지**: docx4j, Apache POI, flexmark-docx-converter, Hilt, DocumentFile, Robolectric.

## 5. 우선순위 충돌 규칙

데이터 안전성 > 오프라인 동작 > 앱 안정성 > Markdown 편집 편의 > DOCX fidelity > 미관

## 6. 테스트

- 변환 로직(markdown/, docx/): 순수 JUnit + `/fixtures` 스냅샷 테스트
- **Robolectric 금지** (불필요) — Android 의존 코드만 instrumentation test
- fixture는 `/fixtures/docx`, `/fixtures/md`에 있으며 기대 결과는 각 EXPECTED.md 참조

## 7. Codex 위임 규칙

- 인터페이스 시그니처가 **확정된** 작업만 Codex에 위임한다
- spike S1(Mammoth import), S3(OOXML writer) 결과 반영 전 **UI 구현 착수 금지**
- spike 리포트는 `/spike/S*-REPORT.md`에 기록한다

## 빌드/검증 명령

```bash
./gradlew assembleDebug        # 디버그 빌드
./gradlew test                 # 순수 JUnit (변환 로직 + 순수성 검사)
./gradlew connectedAndroidTest # 실기기 instrumentation
```
