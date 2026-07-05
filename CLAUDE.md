# mdvault — 개인용 Android 문서 뷰어

## 프로젝트 정의

개인용 Android **순수 문서 뷰어** 앱 (2026-07-05 확정). **유일한 목적은
"내 파일" 등에서 파일을 탭하면 열리는 연결 뷰어**다 — md/txt/html/pdf/이미지/
docx를 받아 잘 읽히게 보여준다. DOCX는 열 때 즉석에서 Markdown으로 변환해
표시하고, 원할 때 "MD 저장"으로 마크다운+이미지를 내보낼 수 있다(읽기 방향
내보내기 — 원본은 절대 건드리지 않는다).

**하지 않는 것 (다시 제안 금지):**
- 편집 (에디터 없음), 볼트/폴더 브라우저 ("내 폴더" 없음), DOCX 생성/export.
  → 2026-07-05 "순수 뷰어" 방향 확정으로 editor/·vault·DOCX-export 코드를 완전 제거함.
- Second Brain 등 외부 서비스 연동 (2026-07-03 확정).

앱 진입은 두 가지뿐: ① 외부 인텐트(ACTION_VIEW/SEND) → 뷰어 단독, 뒤로 가면
원래 앱 복귀. ② 앱 아이콘 → 홈(파일 열기 + 최근 파일) → 뷰어.

## 1. 스택

- Kotlin, Jetpack Compose (Material 3)
- minSdk 29, targetSdk 34, compileSdk 34
- JDK 17 (Android Gradle Plugin 8.x 요구)
- 빌드: Gradle version catalog (`gradle/libs.versions.toml`) — 버전은 반드시 catalog에서만 정의

## 2. 아키텍처

- 단일 모듈 (`:app`), 패키지 경계 엄수:
  - `ui/` — 홈 화면, MainActivity(인텐트 라우팅), 공통 에러 UI, 테마
  - `preview/` — 문서 뷰어 (핵심이자 유일한 동선): SingleDocumentViewerScreen,
    PdfPagesView, DocumentWebView(WebView 공용), PreviewHtmlBuilder
  - `document/` — 문서 종류 판정 + DOCX→Markdown import 파이프라인 조립
  - `storage/` — ContentResolver 기반 파일 읽기, 최근 파일, 에러 타입 (SAF tree
    볼트 코드는 제거됨)
  - `markdown/` — flexmark 기반 변환 엔진 (순수 JVM)
  - `docx/` — DOCX **import**(Mammoth)만 (순수 JVM). export/OOXML writer 제거됨
  - `settings/` — 뷰어 설정: 글자 크기·읽기 위치 (DataStore Preferences)
- DI: 수동 DI (`AppContainer`). **Hilt 금지.** (editor/ 패키지는 없음)

## 3. 변환 파이프라인 격리 (빌드 강제)

`markdown/`, `docx/` 패키지는 Android API import **금지** (import 전용이 된
지금도 유지): `android.*`, `androidx.*` (Context, Uri, ContentResolver,
BitmapFactory 포함 전부). 입출력은 `InputStream`/`OutputStream`/plain data class만 사용한다.

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

데이터 안전성 > 오프라인 동작 > 앱 안정성 > 읽기 경험 > DOCX 변환 fidelity > 미관

## 6. 테스트

- 변환 로직(markdown/, docx/): 순수 JUnit + `/fixtures` 스냅샷 테스트
- **Robolectric 금지** (불필요) — Android 의존 코드만 instrumentation test
- fixture는 `/fixtures/docx`, `/fixtures/md`에 있으며 기대 결과는 각 EXPECTED.md 참조

## 7. Codex 위임 규칙

- 인터페이스 시그니처가 **확정된** 작업만 Codex에 위임한다
- spike 리포트는 `/spike/S*-REPORT.md`에 기록 (S1~S5는 종료됨 — 히스토리 참고용).
  에디터/볼트/OOXML writer 관련 spike(S3/S5)는 순수 뷰어 전환으로 무효

## 빌드/검증 명령

```bash
./gradlew assembleDebug        # 디버그 빌드
./gradlew test                 # 순수 JUnit (변환 로직 + 순수성 검사)
./gradlew connectedAndroidTest # 실기기 instrumentation
```
