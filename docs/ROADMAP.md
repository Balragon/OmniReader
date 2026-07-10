# 로드맵 / Spike 게이트

## Phase 1 — Spike (리스크 제거)

| ID | 내용 | 담당 | 상태 |
|----|------|------|------|
| S0 | fixture 준비 (fixtures/docx, fixtures/md) | Codex | ✅ 완료 (stdlib 생성기, python-docx 불필요) |
| S1 | Mammoth import spike (R8 통과 + fixture crash 0건) | Claude Code | ✅ **종료** — 실기기 67ms/401ms (spike/S1-REPORT.md) |
| S2 | flexmark round-trip + jsoup cleaner | Codex | ✅ 완료 (테스트 통과) |
| S3 | 수제 OOXML writer (의존성 0개) | Claude Code | ✅ 성공 (spike/S3-REPORT.md, Word/GDocs 수동확인 잔여) |
| S4 | SAF repository (DocumentsContract 직접) | Codex | ⚠️ 측정 완료 — 200개 468~969ms로 **목표 500ms 미달** (원인: list()가 매 호출 root부터 재해석, 최적화는 P1) |
| S5 | Compose editor 한글 안정성 판정 (실기기) | Claude Code | Gboard ✅ 통과 (undo는 자체 스냅샷 구현) — Samsung Keyboard(Galaxy)만 잔여 |

**게이트: ✅ 해제 (S1·S3 성공, 2026-07-03) — Phase 2 착수 가능.**

S1 실패(R8 불통과 또는 광범위 crash) 시 Mammoth.js WebView sandbox 대안 검토
— 아키텍처 변경이므로 Architect(설계 세션) 판단 필요.

## Phase 2 — P0 구현

- P0-1 SAF vault (S4 승격) — ✅ 완료 (VaultSetupScreen, 실기기 확인 잔여)
- P0-2 Markdown shell (S5 결과 반영) — ✅ 완료 (FileList/EditorShell/DOCX 가져오기, 실기기 dogfooding 대기)
- P0-3 DOCX import 파이프라인 — ✅ 완료 (DocxToMarkdownImporter + 테스트)
- P0-4 읽기 화면 (Markdown 렌더링 reader — 핵심 동선) — 진행 중
  (Second Brain 연동은 하지 않기로 확정)
- P0-2 완료 시점부터 실기기 dogfooding 시작, friction log 기록

## 뷰어 피벗 (2026-07-04, 사용자 방향 확정)

핵심 용도 = "내 파일"에서 파일 탭 → 열리는 연결 앱 (md/txt/docx/html/pdf/이미지).
- ✅ Batch 1+3: 단일 파일 뷰어 파이프라인 + ACTION_VIEW/SEND 인텐트
- ✅ Batch 2: 홈 재구성 (파일 열기 + 최근 파일 + 내 폴더, 볼트 비강제, Spike 숨김)

## Phase 3 — P1 (dogfooding friction 순서대로)

- ✅ P1 reader polish (2026-07-05): EXIF 회전, GIF 애니메이션, DOCX "MD 저장"
  이미지 포함, 글자 크기(Aa), 문서별 읽기 위치.
- ✅ JSON/CSV reader support (2026-07-10): JSON 들여쓰기, CSV 표 렌더링,
  파일 선택기·외부 VIEW/SEND 연결.

## 순수 뷰어 전환 (2026-07-05, 사용자 방향 확정)

앱을 **순수 문서 뷰어**로 축소. 편집(editor/)·볼트(SAF tree 브라우저)·
DOCX export를 코드째 제거. 진입은 외부 인텐트→뷰어 / 홈(파일 열기+최근)→뷰어뿐.
"MD 저장"은 읽기-방향 내보내기라 유지. 상세는 docs/HANDOFF.md 작업 로그.

**이로써 무효화된 이전 백로그:** SAF 목록 최적화(S4), S5 Samsung Keyboard,
S3 Word/GDocs 수동 확인, 에디터 개선 — 모두 해당 코드가 없어 종료.

**남은 후보:** 앱 이름/아이콘, 그 외 뷰어 dogfooding friction.

**하지 않기로 한 것:** targetSdk 35 (2026-07-05) — 배포는 사이드로드(APK 직접
설치)라 Play 스토어 정책 게이트인 35가 불필요. targetSdk 34로도 안드로이드
15/16에 설치·동작하며, 35는 edge-to-edge 강제 등 새 동작만 떠안음.
