# S5 Spike Report — Compose TextFieldState 에디터 한글 안정성

상태: **Gboard 판정 통과 (undo 수정 포함 전체 확인, 2026-07-04). Samsung Keyboard(Galaxy) 판정만 잔여**

## 구현

- `EditorPort` (editor/EditorPort.kt): buffer, selection, undo/redo, 스크롤 위치.
  판정 실패 시 AppCompatEditText + AndroidView 구현으로 이 인터페이스 뒤에서만 교체
- `ComposeEditorPort`: TextFieldState 기반. undo/redo는 **자체 debounce 스냅샷
  스택** — 아래 "undoState 판정" 참조 (원래 지침이던 undoState는 폐기)
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
| Gboard (에뮬레이터) | ✅ 없음 | ✅ 없음 | ✅ 없음 | 2026-07-04, 스냅샷 undo 적용 후 사용자 확인 |

## 2026-07-04 Gboard 예비 판정 (에뮬레이터)

- 조합 깨짐 / 커서 점프: **이상 없음** (사용자 수동 판정)
- undo: "작동 안 함" 보고 → instrumentation으로 재현·원인 확정

### undoState 판정: 폐기

`TextFieldState.undoState`는 한글 IME 조합의 자모 단계(ㅅ→서→설)를 **전부
개별 undo 항목으로 기록**한다 — merge가 연속 "삽입"에만 적용되고 조합
업데이트는 "치환"이라 merge 불가 (foundation 1.7.2 TextUndoManager 소스 확인).
Undo 한 번에 자모 한 단계만 돌아가 사실상 무용.
InputConnection을 직접 잡아 setComposingText를 주입하는
`EditorImeCompositionUndoTest`로 결정적으로 재현했다.

처치: ComposeEditorPort에 자체 undo — 타이핑이 350ms 멈출 때마다 스냅샷 기록
(버스트 단위 undo, 용량 100). EditorPort 인터페이스 불변, 화면 코드 불변.
androidTest 5개(일반 타이핑 2 + 조합 3)로 회귀 방지.

2026-07-04 사용자 확인: 수정 후 undo 정상 작동. 잔여: Samsung Keyboard 전체 판정(Galaxy).
