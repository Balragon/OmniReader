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

- ✅ DOCX export S3 승격 (reader "DOCX" 버튼, 2026-07-04 에뮬레이터+LibreOffice 검증)
SAF 목록 조회 최적화(documentId 캐시 — S4 측정 미달분), 읽기 경험 개선(글꼴/여백/다크모드), search,
share target, table helper, diff view — 우선순위는 dogfooding friction 순
