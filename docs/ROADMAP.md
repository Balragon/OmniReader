# 로드맵 / Spike 게이트

## Phase 1 — Spike (리스크 제거)

| ID | 내용 | 담당 | 상태 |
|----|------|------|------|
| S0 | fixture 준비 (fixtures/docx, fixtures/md) | Codex | ✅ 완료 (stdlib 생성기, python-docx 불필요) |
| S1 | Mammoth import spike (R8 통과 + fixture crash 0건) | Claude Code | 대기 |
| S2 | flexmark round-trip + jsoup cleaner | Codex | ✅ 완료 (테스트 통과) |
| S3 | 수제 OOXML writer (의존성 0개) | Claude Code | 대기 |
| S4 | SAF repository (DocumentsContract 직접) | Codex | 코드 완료 — 실기기 500ms 측정 대기 |
| S5 | Compose editor 한글 안정성 판정 (실기기) | Claude Code | 대기 |

**게이트: S1, S3 리포트(spike/S*-REPORT.md)가 나오기 전에 Phase 2(UI) 착수 금지.**

S1 실패(R8 불통과 또는 광범위 crash) 시 Mammoth.js WebView sandbox 대안 검토
— 아키텍처 변경이므로 Architect(설계 세션) 판단 필요.

## Phase 2 — P0 구현

- P0-1 SAF vault (S4 승격) — Codex
- P0-2 Markdown shell (S5 결과 반영) — Codex
- P0-3 DOCX import 파이프라인 — Claude Code 조립 + Codex 테스트
- P0-4 Second Brain 최소 연동 — Codex
- P0-2 완료 시점부터 실기기 dogfooding 시작, friction log 기록

## Phase 3 — P1 (dogfooding friction 순서대로)

DOCX export(S3 승격), table helper, search, share target, diff view
