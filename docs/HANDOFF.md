# HANDOFF — Claude ↔ Codex 인수인계 문서

> 목적: Claude 플랜 한도 도달 시 Codex가(또는 반대로) 이 문서 하나로 작업을
> 이어받는다. **작업을 마친 에이전트는 반드시 "현재 상태"와 "다음 작업"을
> 갱신하고 커밋할 것.** 규칙의 원본은 CLAUDE.md (여기 복제 금지).

최종 갱신: 2026-07-04 (Codex)

## 제품 한 줄

**"내 파일"에서 md/txt/docx/html/pdf/이미지를 탭하면 열리는 오프라인 문서
뷰어.** 부가로 폴더(볼트) 노트 관리 + DOCX 가져오기/내보내기. 사용자 요구:
단순할 것, 개발자 티 내지 말 것. 외부 서비스 연동 없음.

## 현재 상태 (전부 main에 push됨)

- 뷰어 피벗 완료: ACTION_VIEW/SEND 인텐트 → SingleDocumentViewerScreen
  (md/txt 렌더, docx 즉석 변환+MD 저장, html JS차단 표시, pdf 내장 렌더러,
  이미지 화면맞춤+핀치줌). 홈 = 파일 열기 + 최근 파일 + 내 폴더.
- 볼트(내 폴더): 파일 목록(전 형식) → md는 Reader→편집기, DOCX 가져오기,
  DOCX 내보내기(reader의 "DOCX" 버튼), 새 노트 → 편집기 직행,
  `.md` 노트는 목록/편집기에서 확인창 후 삭제.
- 폴더 변경 화면(VaultSetup): 시스템 뒤로가기 → 기존 볼트가 있으면 파일
  목록으로 복귀, 아직 볼트가 없으면 홈으로 복귀.
- 에디터: Compose TextFieldState + **자체 debounce(350ms) 스냅샷 undo**
  (undoState는 한글 조합 자모 단계를 개별 기록해 폐기 — spike/S5-REPORT.md).
- 검증 인프라: JVM 테스트 ~28개, instrumentation 5개(한글 조합 포함),
  fixtures/ 20개, 에뮬레이터 AVD `mdvault-api34`.
- 실기기 판정: S1 종료(67ms/401ms), S5 Gboard 통과, S4 목표 미달(아래 참조).

## 다음 작업 (우선순위 순)

1. **S5 Samsung Keyboard 판정** — Galaxy에서 debug 빌드 설치 후
   Spike(폴더 설정 화면 하단, debug에서만 노출) → S5. 절차와 기록란:
   spike/S5-REPORT.md. 통과 시 spike 전 항목 종료.
2. **SAF 목록 조회 최적화** — S4 측정: 200개 468~969ms (목표 500ms).
   원인: VaultRepository.list()가 매 호출 root부터 경로 재해석 + 커서 이중
   순회. 개선안: documentId 캐시(path→docId, 쓰기 시 무효화).
   측정: 앱 Spike → S4 버튼 (perf/ 폴더에 파일 200개 필요).
3. 읽기 경험 개선(dogfooding friction 순): 글꼴 크기 설정, pdf 핀치 줌 등
   사용자가 보고하는 순서대로.
4. S3 잔여: Word/Google Docs 수동 확인 (spike/S3-MANUAL-VERIFICATION.md).

## 지뢰 (모르면 다시 밟는다)

| 지뢰 | 내용 | 방어 |
|---|---|---|
| R8 × flexmark | 클래스 병합이 DependencyResolver를 깨서 시작 즉시 crash | `-keepnames com.vladsch.flexmark.**` 유지 (proguard-rules.pro) |
| Android SAX × mammoth | libcore가 SAXParserFactoryImpl 하드코딩, 보안 feature 거부 → 기기에서 import 전멸. JVM 테스트로 재현 불가 | 패치 jar `app/libs/mammoth-1.9.0-android.jar` 사용. 재생성: tools/mammoth-android-patch/README.md |
| WebView 100vh | wide-viewport 모드에서 vh 높이 0 → 검은 화면 | 이미지 뷰어는 position:fixed + object-fit:contain 사용 |
| TextFieldState.undoState | 한글 조합 자모 단계를 전부 개별 undo 항목으로 기록 | 사용 금지 — ComposeEditorPort의 자체 스냅샷 undo 유지 |
| connectedAndroidTest | 종료 시 앱 제거 → 볼트 설정 소실 | 테스트 후 release 재설치 + 볼트 재선택 |
| API 35 DocumentsProvider | 자체 provider 직접 접근 SecurityException | 성능 측정은 앱 내 Spike 화면으로 (instrumentation 테스트는 @Ignore) |
| Mammoth XML 제어문자 | 불법 제어문자에 SAX crash | DocxXmlSanitizer가 전처리 (DOCTYPE 제거 포함 — XXE 방어 대체) |

## 검증 루틴 (변경 후 항상)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew test assembleRelease          # 1. JVM + R8 빌드
# 2. release 변경 시 에뮬레이터 스모크 (필수):
~/Library/Android/sdk/emulator/emulator -avd mdvault-api34 -no-window -no-audio &
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n dev.gold.mdvault/.ui.MainActivity
adb logcat -d | grep FATAL              # 0건이어야 함
# 3. 에디터 관련 변경 시:
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=dev.gold.mdvault.editor
```

## 히스토리 요약 (자세한 것은 git log + spike/*.md)

Phase 0 scaffold → spike S0~S5 (Mammoth/OOXML writer/SAF/에디터 검증) →
P0 vault 앱 → **뷰어 피벗** (2026-07-04, 사용자 방향 확정) → dogfooding 수정.
아키텍처 결정 근거는 spike/S1-REPORT.md, S3-REPORT.md, S5-REPORT.md에 있음.
