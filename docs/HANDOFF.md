# HANDOFF — Claude ↔ Codex 인수인계 문서

> 목적: Claude 플랜 한도 도달 시 Codex가(또는 반대로) 이 문서 하나로 작업을
> 이어받는다. **작업을 마친 에이전트는 반드시 "현재 상태"와 "다음 작업"을
> 갱신하고 커밋할 것.** 규칙의 원본은 CLAUDE.md (여기 복제 금지).

최종 갱신: 2026-07-04

## 작업 로그 (append-only — 최신이 위, 전면 재작성 금지)

> 규칙: 세션을 마칠 때 아래 템플릿으로 **엔트리 하나를 이 안내문 바로 아래에
> 추가**한다. 코드 변경 내용은 커밋 메시지/diff가 원본이므로 반복하지 말고,
> **diff에 안 보이는 것만** 적는다. ⚠️는 다음 에이전트가 반드시 알아야 하는
> 것(공용 시그니처/계약 변경, 새 지뢰, 미검증 항목)에만 붙인다.
>
> ```
> ### YYYY-MM-DD <에이전트> (<커밋범위: abc1234..def5678>)
> - 요지: <한 줄 — 무엇을 왜>
> - 검증: <실행한 명령/결과. 에뮬레이터 스모크 여부 명시. 안 했으면 "미검증">
> - ⚠️ <계약 변경/새 지뢰/미검증 — 없으면 이 줄 생략>
> - 보류: <하다 만 것, 알게 됐지만 안 고친 것 — 없으면 생략>
> ```

### 2026-07-05 Claude (텍스트 문서 뒤로가기 버그 수정)
- 요지: md/txt/html/docx 뷰어에서 시스템 뒤로가기를 누르면 홈으로 안 가고 앱이
  종료되던 버그. 원인: 이미지/PDF는 MediaViewerScaffold의 BackHandler가 가로채는데
  텍스트(Web) 경로엔 BackHandler가 없어 액티비티가 그대로 finish됨.
  수정: Web 경로에도 BackHandler(onBack=onBack) 추가 → 모든 형식이 상단 ←와
  동일하게 동작(홈에서 열면 홈 복귀, 파일앱 탭으로 열면 파일앱 복귀).
- 검증: 에뮬레이터에서 md/docx 피커→뒤로가기=홈, 외부 docx→뒤로가기=Files 복귀
  확인(topResumedActivity로 판정), release FATAL 0, Galaxy 설치.

### 2026-07-05 Claude (홈 UI 개편 + 최근 파일 재열기 수정)
- 요지: ①홈 헤더를 "mdvault"→"OmniReader"(O 링 로고 마크+"문서 뷰어" 부제),
  파란 "파일 열기" 카드, 최근 파일을 타입 배지(MD/PDF/DOC/IMG/HTM/TXT)+시간
  카드로 개편(HomeScreen.kt 전면 재작성, 의존성 0 추가). ②최근 파일 탭이
  안 열리던 버그 수정.
- ⚠️ 최근 파일 근본 원인/설계 결정: 파일 앱 탭(ACTION_VIEW)으로 연 문서는
  provider가 **일시 권한만** 부여(logcat "requires ACTION_OPEN_DOCUMENT") →
  재실행 후 openInputStream이 SecurityException → 죽은 항목이 됐던 것.
  수정: (a) MainActivity가 외부 인텐트 URI에 takePersistableUriPermission
  시도(persistReadIfPossible), (b) 뷰어는 **영구 권한을 실제 보유한 문서만**
  최근에 기록(hasPersistedReadPermission 게이트). 결과: 최근 목록은 항상
  재열기 가능한 항목만 → 죽은 항목·"권한 만료" churn 없음.
  **사용자 결정(2026-07-05): 파일앱 탭 문서는 최근에 안 뜨는 채로 둔다**
  (사본 캐싱은 안 함 — 단순함 우선). 즉 최근은 앱 내 "파일 열기" 피커로
  연 문서만 채워진다. 다운로드 provider VIEW 그랜트는 persistable 아님을
  에뮬레이터로 확인함.
- 검증: 빌드 + 에뮬레이터(새 홈 스샷, 피커로 연 md가 강제종료 후 최근에
  남고 탭→재열림 확인, 파일앱 탭 문서는 최근 미기록 확인), release FATAL 0,
  Galaxy 설치.

### 2026-07-05 Claude+Codex (다국어 지원 — 영어 기본 + 한국어, wf_e088d49e7929)
- 요지: UI 문자열을 리소스화해 **기기 언어를 자동으로 따르도록** 함. 그 전엔
  전부 한국어 하드코딩. res/values/strings.xml(영어=기본, 비한국어 기기용) +
  res/values-ko/strings.xml(한국어). 36개 키(app_name 포함), ko는 35개(app_name 제외).
- 배선: Composable은 stringResource(R.string.…); 비-Composable은 context.getString.
  loadDocument()·relativeTime()에 context 파라미터 추가. VaultErrorUi를
  해석 문자열 대신 **@StringRes messageRes + rawMessage 폴백**으로 리팩터,
  표시 시점에 VaultErrorUi.text()(@Composable)로 해석.
- ⚠️ 깊은 SAF/디코드 예외 메시지는 영어 리터럴로 통일(사용자에겐 지역화된
  상위 메시지만 보이고, 이 detail은 로그/희귀 케이스용). 새 문자열은 두 파일
  모두에 같은 key로 추가할 것(키 불일치 시 런타임 누락).
- 검증: 빌드+테스트, 에뮬레이터에서 `cmd locale set-app-locales dev.gold.mdvault
  --locales en-US|ko-KR`로 홈·뷰어 양쪽 언어 전환 확인(영어 "Open file"/"Save as MD",
  한국어 "파일 열기"/"MD 저장"), release FATAL 0. Galaxy 설치는 재연결 시.

### 2026-07-05 Claude (v1.0.0 공개 배포)
- 요지: GitHub 저장소를 PUBLIC 전환, Release v1.0.0에 서명된 APK 첨부 →
  누구나 사이드로드 다운로드 가능. README를 사용자용으로 재작성.
  버전 0.1.0→1.0.0. release 빌드가 debug 서명 → **릴리스 키 서명**으로 전환.
- ⚠️ **릴리스 서명 키 (백업 필수, 분실 시 업데이트 불가):**
  `release.keystore`(alias=omnireader) + `keystore.properties`(비밀번호) — 둘 다
  **gitignore, 리포에 없음, 사용자 로컬에만 존재**. build.gradle.kts가
  keystore.properties 있으면 릴리스 키로, 없으면 debug로 폴백 서명.
  인증서 CN=OmniReader, SHA-256 b656c3a0…52f6d16. 이 키를 잃으면 같은 앱으로
  업데이트 못 함(사용자가 삭제 후 재설치해야 함) → 안전한 곳에 백업할 것.
- 다음 릴리스 절차: versionCode/versionName 올림 → `./gradlew assembleRelease`
  → `cp app-release.apk OmniReader-x.y.z.apk` → `gh release create vX.Y.Z <apk>
  --repo Balragon/mdvault --title ... --notes ...`.
- 공개 링크: 릴리스 https://github.com/Balragon/mdvault/releases/tag/v1.0.0 /
  고정 다운로드 https://github.com/Balragon/mdvault/releases/latest/download/OmniReader-1.0.0.apk
- 검증: apksigner로 릴리스 키 서명 확인, Galaxy에 릴리스-서명 APK 재설치·실행,
  latest/download URL이 인증 없이 302→APK(application/vnd.android.package-archive).

### 2026-07-05 Claude (런처 아이콘 = 사용자 제공 3D 사진)
- 요지: 벡터 아이콘 대신 사용자가 준 3D 렌더 사진(파란 폴더+격자 문서+O 링)을
  그대로 런처 아이콘으로 사용. drawable/ic_launcher_foreground.xml(벡터) 삭제,
  drawable-nodpi/ic_launcher_foreground.png(512²) 신설. 어댑티브 XML은 그대로
  이 foreground를 참조. 배경은 사진 배경색과 맞춘 solid #FFE1E6ED.
- ⚠️ 어댑티브 마스크 잘림 방지: 원본은 폴더가 세로로 커서(≈748px/1152px) 그대로
  쓰면 원형 마스크에 잘림. PIL로 배경색(225,230,237) 정사각 캔버스에 사진을
  폴더 중심 기준 배치(폴더가 아이콘의 54% 차지)해 여백 확보 → 잘림 없음.
  재생성: 스크립트는 이 커밋 참조(폴더 중심 391,615 / folder_h 748 / frac 0.54).
  원본 사진은 리포에 없음(사용자 Desktop). 아이콘 바꾸려면 새 사진으로 동일 처리.
- 검증: 빌드 + 에뮬레이터 앱서랍(원형 마스크에서 사진 전체 표시, 이음새 없음),
  release FATAL 0, Galaxy 설치.

### 2026-07-05 Claude (앱 이름 OmniReader + 런처 아이콘 — 벡터, 이후 사진으로 교체됨)
- 요지: 앱 이름을 "OmniReader"로 확정, 어댑티브 런처 아이콘 신설. 그동안
  res/ 디렉토리 자체가 없어 아이콘=시스템 기본, 이름=매니페스트 하드코딩
  "mdvault"였음. res/values/strings.xml(app_name), 어댑티브 아이콘
  (drawable/ic_launcher_foreground·background 벡터 + mipmap-anydpi-v26/
  ic_launcher·_round), 매니페스트에 icon/roundIcon/label 연결.
- 아이콘 디자인: 파란 폴더 포켓이 흰 문서(접힌 모서리+텍스트 라인)를 물고,
  포켓에 흰 O 링(OmniReader의 O). 사용자 제공 참고 이미지 기반. 전부 벡터라
  PNG mipmap 불필요(minSdk 29 ≥ 26, 어댑티브만으로 충분).
- 검증: 빌드 + 에뮬레이터 앱 서랍 스크린샷(원형 마스크에서 아이콘·이름 정상),
  release FATAL 0, Galaxy 설치.
- 보류: 테마 아이콘(monochrome 레이어)은 미추가 — 필요 시 안드로이드 13+
  단색 아이콘용으로 나중에.

### 2026-07-05 Claude+Codex (순수 뷰어 전환 — 편집·볼트·DOCX export 완전 제거, wf_5b227a57f227)
- 요지: 사용자가 "순수 뷰어" 방향 확정 → editor/ 패키지, 볼트(SAF tree
  브라우저: VaultRepository/SafDocumentRepository/FileList/VaultSetup/
  MarkdownReaderScreen), DOCX export(VaultDocxExporter/DocxExportEngine/
  OoxmlWriter/SimpleOoxmlDocxExportEngine) 및 관련 테스트를 삭제. 앱 진입은
  외부 인텐트→뷰어 / 홈(파일 열기+최근 파일)→뷰어 둘뿐. MainActivity의
  Screen enum·Spike 하네스 전부 제거, HomeScreen에서 "내 폴더" 제거.
- ⚠️ 삭제 전 필수 추출: MarkdownReaderScreen.kt 안의 뷰어 공용 헬퍼
  (vaultBaseUrl, DocumentWebViewClient, 글자크기·읽기위치 함수)를 새 파일
  preview/DocumentWebView.kt로 옮겼음. DocumentWebViewClient는 볼트 전용
  onOpenNote 인자를 제거한 버전.
- ⚠️ DOCX "MD 저장"은 유지: Codex가 이걸 편집 기능으로 오판해 지웠으나
  사용자 지시로 Claude가 복원(ViewerState.Web의 savableMarkdown/assetRoot/
  assetRelativePaths + 툴바 버튼 + saveMarkdownWithAssets SAF 헬퍼). MD 저장은
  뷰어의 읽기-방향 내보내기라 순수 뷰어와 공존.
- 검증: gradlew test assembleDebug/Release + 에뮬레이터 스모크(홈 정리 확인,
  Files 탭→DOCX 렌더+MD 저장 5개 이미지 포함, 이미지 EXIF, release R8에서
  DOCX import 재확인), 삭제 심볼 잔존 참조 0, FATAL 0. release APK 3.68→3.24MB.
- 보류: 남은 후보 — 앱 이름/아이콘, targetSdk 35. (SAF 목록 최적화·S4·S5·
  editor 관련 백로그는 순수 뷰어 전환으로 전부 무효.)

### 2026-07-05 Claude+Codex (P1 reader polish, wf_29702ed7feed)
- 요지: P1 배치 — ①이미지 뷰어를 ImageDecoder로 전환(EXIF 회전 자동 적용,
  GIF/애니메이션 WebP 재생, 샘플링 OOM 가드 유지) ②DOCX "MD 저장"이
  이미지 포함 저장(에셋 있으면 OpenDocumentTree → 문서명 폴더(-2/-3 충돌
  회피)에 .md+상대경로 에셋, 없으면 기존 CreateDocument 유지)
  ③settings/ReaderSettingsRepository 신설 — Aa 글자 크기(85~150% 순환,
  WebView textZoom)와 문서별 읽기 위치(WebView 스크롤 비율 / PDF 페이지
  인덱스+오프셋, MRU 100개) 저장. targetSdk 35는 사용자 지시로 제외.
- 검증: gradlew test assembleDebug/Release + 에뮬레이터 스모크 전 항목 통과
  (EXIF 세로 표시, GIF 프레임 diff로 애니메이션 확인, PDF 7페이지 복원,
  md 스크롤 복원, Aa 100→130% 확대, MD 저장 이미지 5개+images-2 접미사,
  release에서 DOCX 변환+GIF 재확인), FATAL 0
- ⚠️ 새 지뢰: Downloads provider는 같은 파일에 오픈마다 다른 URI를 준다
  (raw:↔msf: 전환) — 읽기 위치 키를 uri.toString()으로 하면 복원 실패.
  파일명+크기 키("doc:이름:크기")로 수정함 (Claude 직접 수정 3곳).
  RecentFilesRepository의 URI 중복 백로그도 같은 원인.
- 보류: WebView 위치 복원이 onPageFinished+post 1회라 이미지 많은 문서는
  높이 안정화 전에 복원될 수 있음 (리뷰 WARN — 실측에서는 문제 없었음).
  Galaxy(Samsung My Files)에서 URI 안정성/실사용 확인은 사용자 몫.

### 2026-07-05 Claude (이미지 열기 회귀 + PDF 중앙 정렬)
- 요지: P0-B의 2-pass 이미지 디코딩 회귀 수정 — inJustDecodeBounds 모드
  decodeStream은 성공해도 null을 반환하는데 use{} 반환값에 elvis를 걸어
  모든 이미지가 "파일이 이동되었거나 삭제되었습니다"로 실패했음. 스트림
  null 체크와 디코드 결과 분리. PDF는 화면보다 짧으면 세로 중앙 정렬.
- 검증: gradlew test assembleRelease + 에뮬레이터(정상 이미지 열림, 1페이지
  PDF 중앙 정렬 화면 확인), FATAL 0
- ⚠️ 새 지뢰: use{}의 반환값은 람다 결과 — inJustDecodeBounds decodeStream과
  조합 시 elvis 오판. 지뢰 표에 추가할 가치 있음
- 보류: fixtures의 JPEG들(images.docx 유래)은 헤더만 유효한 깨진 파일 —
  S0 생성기가 stdlib로 만든 것. BitmapFactory/PIL 모두 디코드 거부.
  실사용 무관하나 이미지 관련 테스트에 쓰지 말 것 (tall.png는 정상)

### 2026-07-04 Claude+Codex (P0 하드닝, wf_2d4a028fa43b)
- 요지: 출시 검토 P0 3건 — ①rememberSaveable+에디터 draft 자동보관/복원
  ②대용량 게이트(텍스트 4MB 부분표시·에디터 2MB 거부·이미지 다운샘플·DOCX
  50MB)+한국어 안내 ③VaultError 모델링(권한소실/파일없음/제공자오류 →
  한국어 복구 CTA, raw exception 노출 제거)
- 검증: gradlew test assembleRelease + 에디터 instrumentation 5개 + 에뮬레이터
  실동작(회전 후 reader 유지, 프로세스 kill 후 draft 복원 프롬프트→내용 일치,
  14MB md 부분표시 안내, FATAL 0)
- ⚠️ VaultRepository/SafDocumentRepository가 VaultError를 던지도록 변경
  (Exception 하위라 기존 호출부 호환). 에디터에 draft 파일 경로 규약 추가:
  cacheDir/drafts/<sha12-of-path>.md
- 보류: VaultError 중 PermissionLost 실기 재현 테스트는 미실시 (코드 경로만)

### 2026-07-04 Claude+Codex (미디어 크롬 토글 + 출시 검토, wf_7234c6a5baf6)
- 요지: 갤러리식 몰입 토글(탭→상단바+상태바+내비바 동시 표시/숨김, 콘텐츠
  edge-to-edge) Codex 구현 + 3렌즈 출시 검토(발견 다수 → P0/P1/P2 보고서)
- 검증: gradlew test assembleRelease + 에뮬레이터 토글 시퀀스 화면 확인
  (숨김/복귀/이탈 시 시스템바 복원), FATAL 0
- ⚠️ P0 3건 확인: ①회전/프로세스 재생성 시 상태 소실(rememberSaveable 부재)
  ②대용량 파일 OOM 경로(전체 readBytes) ③SAF 오류가 raw exception으로 노출
- 보류: P1/P2 백로그는 이 로그 아래 "다음 작업" 절과 워크플로우 결과 참조

### 2026-07-04 Claude (7b813f8 스모크 검증 — 코드 변경 없음)
- 요지: Codex 미검증분 스모크 완료 — 노트 삭제(다이얼로그→UI·파일시스템 제거),
  이미지 네이티브 뷰어(세로 3000px 한 화면 contain+고정 상단바), PDF 새 크롬
  렌더링, 최근 파일 권한 만료 자동 정리. 전 과정 FATAL 0.
- 검증: gradlew test assembleRelease + 에뮬레이터 release 스모크 (화면 확인)
- ⚠️ 핀치 줌 실제 제스처는 ADB로 재현 불가 — Galaxy에서 사용자 확인 필요
- 보류: 최근 파일에 동일 파일이 URI만 다르게 중복 표시될 수 있음
  (dedup이 uri 문자열 기준 — displayName+크기 기준 보강 후보)

### 2026-07-04 Codex (ad3028e..HEAD)
- 요지: 이미지/PDF를 같은 미디어 뷰어 크롬으로 통일(고정 뒤로가기 바)하고,
  PDF에 핀치 줌/확대 후 이동 레이어를 추가. 테스트용 mdvault PNG/JPG/PDF 정리.
- 검증: `./gradlew test assembleRelease` 통과. release APK 에뮬레이터 설치 후
  JPG/PDF content URI 열기, 고정 `←`/파일명/시스템 내비게이션 바/PDF 페이지 렌더
  UI 트리 확인. Galaxy release APK 설치 성공 및 crash buffer 이상 없음.
- ⚠️ ADB 자동화로 실제 두 손가락 핀치 동작 자체는 재현하지 못함. PDF 줌은
  Compose transformable 경로로 빌드 검증됨.
- 보류: Galaxy는 검증 중 잠금 화면이 앞에 떠서 UI 트리 확인은 중단
  (잠금 해제 우회 안 함). 설치와 crash buffer 확인까지만 완료.

### 2026-07-04 Codex (0f18022..5998326)
- 요지: 노트 삭제(확인 다이얼로그+최근 목록 정리), 이미지 뷰어를 WebView에서
  네이티브 몰입형으로 교체, 실행 중 새 intent 즉시 반영
- 검증: (이 로그 프로토콜 도입 전 작업이라 기록 누락 — 다음 세션에서
  에뮬레이터 스모크 필요)
- ⚠️ VaultRepository.delete() 추가 (공용 계약 확장)
- ⚠️ 이미지 경로가 WebView → 네이티브로 교체 — 핀치 줌 동작 미확인,
  "지뢰"의 WebView 100vh 항목은 이미지에 한해 해당 없어짐

### 2026-07-04 Claude (ee4196a..f11351a)
- 요지: dogfooding 수정 3건(이미지 화면맞춤·새 노트→편집기·Spike debug 전용)
  + 인수인계 문서 체계(AGENTS.md/HANDOFF.md) 도입
- 검증: gradlew test/assembleRelease + 에뮬레이터 스모크(세 건 모두 화면 확인)

## 제품 한 줄

**"내 파일"에서 md/txt/docx/html/pdf/이미지를 탭하면 열리는 오프라인 순수
문서 뷰어.** 편집·볼트·DOCX 생성 없음(2026-07-05 제거). 사용자 요구:
단순할 것, 개발자 티 내지 말 것. 외부 서비스 연동 없음.

## 현재 상태 (전부 main에 push됨)

- 순수 뷰어 (2026-07-05 전환). 진입 2가지:
  ① 외부 ACTION_VIEW/SEND 인텐트 → SingleDocumentViewerScreen, 뒤로 가면
     원래 앱 복귀. ② 앱 아이콘 → HomeScreen(파일 열기 + 최근 파일) → 뷰어.
- 뷰어 기능: md/txt 렌더, docx 즉석 변환+렌더+"MD 저장"(마크다운+이미지 폴더
  내보내기, 원본 불변), html JS차단 표시, pdf 내장 렌더러+핀치 줌, 이미지
  네이티브 화면맞춤+핀치 줌+EXIF 회전+GIF 애니메이션, 이미지/PDF 고정 상단 바,
  텍스트 문서 Aa 글자 크기, 문서별 읽기 위치 기억.
- 편집·볼트·DOCX export는 코드째 제거됨 (editor/, VaultRepository,
  SafDocumentRepository, FileList/VaultSetup/MarkdownReaderScreen,
  VaultDocxExporter, DocxExportEngine/OoxmlWriter/SimpleOoxmlDocxExportEngine).
- 검증 인프라: JVM 테스트(변환·순수성·markdown·import), fixtures/,
  에뮬레이터 AVD `mdvault-api34`. (editor/SAF instrumentation 테스트는 제거됨.)

## 다음 작업 (우선순위 순)

1. 읽기 경험 개선은 사용자가 dogfooding 중 보고하는 순서대로.
   (글자 크기·읽기 위치·GIF/EXIF·MD 저장 이미지 포함은 2026-07-05 완료.
   앱 이름 "OmniReader"·런처 아이콘도 2026-07-05 완료.)

### 하지 않기로 한 것
- **targetSdk 35 안 올림** (2026-07-05 확정): 배포 방식이 사이드로드(APK 직접
  설치)라 Play 스토어 정책 게이트인 targetSdk 35가 불필요. 34로도 안드로이드
  15/16에 설치·동작. 올리면 edge-to-edge 강제 등 새 동작만 떠안음.
- SAF 목록 최적화(S4)·S5 Samsung Keyboard·S3 수동 확인·에디터 개선: 편집/볼트
  제거로 해당 코드가 없어 종료.

## 지뢰 (모르면 다시 밟는다)

| 지뢰 | 내용 | 방어 |
|---|---|---|
| R8 × flexmark | 클래스 병합이 DependencyResolver를 깨서 시작 즉시 crash | `-keepnames com.vladsch.flexmark.**` 유지 (proguard-rules.pro) |
| Android SAX × mammoth | libcore가 SAXParserFactoryImpl 하드코딩, 보안 feature 거부 → 기기에서 import 전멸. JVM 테스트로 재현 불가 | 패치 jar `app/libs/mammoth-1.9.0-android.jar` 사용. 재생성: tools/mammoth-android-patch/README.md |
| 이미지/PDF 뷰어 크롬 | 숨김/터치 reveal 방식은 자동화에서 컨트롤 표시가 안정적으로 잡히지 않았음 | 이미지/PDF는 같은 고정 상단 바(`←`+파일명) 사용. 이미지/PDF 본문은 검은 배경 + 화면맞춤/줌 유지 |
| use{}+inJustDecodeBounds | bounds 모드 decodeStream은 성공해도 null → use 반환값 elvis가 FNF 오판 | 스트림 null 체크와 디코드 결과 체크 분리 (현재는 ImageDecoder로 교체) |
| Downloads URI 불안정 | 같은 파일인데 오픈마다 raw:↔msf: 문서 ID가 바뀜 → URI 키 저장은 재오픈 시 miss | 문서 식별 키는 파일명+크기 ("doc:이름:크기") 사용 |
| VIEW 인텐트 권한 비영구 | 파일앱 탭(ACTION_VIEW)은 일시 권한만 → 재실행 후 그 URI openInputStream이 SecurityException | 영구 권한 보유한 문서만 최근 기록. 파일앱 탭 문서는 최근에 안 남김(사용자 결정) |
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
# 3. UI 자동화 스모크: uiautomator dump + input tap (tools/tap 패턴은 세션 메모리 참고).
#    Files 앱은 mtime 정렬 → 오래된 push 파일은 `touch`로 최신화해야 목록 상단에 뜸.
```

## 히스토리 요약 (자세한 것은 git log + spike/*.md)

Phase 0 scaffold → spike S0~S5 (Mammoth/OOXML writer/SAF/에디터 검증) →
P0 vault 앱 → **뷰어 피벗** (2026-07-04) → P1 reader polish (2026-07-05) →
**순수 뷰어 전환** (2026-07-05, 편집·볼트·export 제거). spike 리포트와 제거된
에디터/볼트/OOXML 관련 근거는 히스토리 참고용 (더 이상 코드에 없음).
