# S5 Spike Report — Compose TextFieldState 에디터 한글 안정성

상태: **구현 완료, 실기기 판정 대기** (2026-07-03)

## 구현

- `EditorPort` (editor/EditorPort.kt): buffer, selection, undo/redo, 스크롤 위치.
  판정 실패 시 AppCompatEditText + AndroidView 구현으로 이 인터페이스 뒤에서만 교체
- `ComposeEditorPort`: TextFieldState 기반. undo/redo는 자체 구현하지 않고
  `TextFieldState.undoState` 사용 (experimental — Foundation은 compose BOM
  2024.09.03으로 version catalog에 고정)
- `MarkdownEditorScreen`: BasicTextField(state) + Undo/Redo 버튼
- 판정용 50KB 한글 샘플은 `s5KoreanSample()`이 기기에서 생성
  (마크다운 문법 + 한글 혼합, 도깨비불 유발 문구 포함)

## 실기기 판정 절차 (Galaxy 연결 후)

1. `adb install app/build/outputs/apk/release/app-release.apk`
2. 앱 실행 → "S5 Editor" 버튼 → 50KB 샘플 로드 확인
3. **Samsung Keyboard**로: 문서 중간에 한글 삽입 / 조합 중(자모 결합 중) 커서
   이동 / 받침 있는 글자 삭제 / undo 연타 → redo 연타 — 각 10회 이상 반복
4. **Gboard**로 3번 반복
5. 관찰 항목: 조합 깨짐(자모 분리·중복 입력), 커서 점프, undo 후 버퍼 불일치,
   IME 조합 밑줄이 엉뚱한 위치에 남는 현상

## 판정 기준

- 조합 깨짐 0건 → Compose 확정
- 발생 시 → 재현 절차를 이 리포트에 기록하고 AppCompatEditText 구현으로 전환
  (ComposeEditorPort만 교체, MarkdownEditorScreen은 EditorPort 의존으로 리팩터)

## 결과 기록란

| 키보드 | 조합 깨짐 | 커서 점프 | undo 불일치 | 비고 |
|---|---|---|---|---|
| Samsung Keyboard | ⬜ | ⬜ | ⬜ | |
| Gboard | ⬜ | ⬜ | ⬜ | |
