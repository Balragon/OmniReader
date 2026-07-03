# S3 수동 검증 절차서

샘플 생성: `./gradlew test` → `app/build/outputs/spike/s3-sample.docx`

## 1. 열기 검증 (각 프로그램에서)

| 프로그램 | 확인 항목 | 결과 |
|---|---|---|
| LibreOffice (headless PDF 변환) | 오류 없이 열림, 서식 렌더링 | ✅ 2026-07-03 통과 |
| Microsoft Word | "파일이 손상되었습니다" 경고 없이 열림 | ⬜ 미실시 |
| Google Docs (업로드 후 열기) | 변환 오류 없음 | ⬜ 미실시 |

각 프로그램에서 확인: 제목 1-3 위계 / 굵게·기울임 / 불릿·순번 목록(중첩 포함) /
표(테두리, 헤더행, 열 정렬) / 하이퍼링크 클릭 가능 / 이미지 표시 / 한글 정상.

## 2. 재저장 round-trip 검증

1. 각 프로그램에서 s3-sample.docx를 열고 **다른 이름으로 재저장** (.docx)
2. 재저장본을 `fixtures/` 밖 임시 폴더에 두고 Mammoth로 재import:
   MammothDocxImportEngineTest의 importFixture를 참고해 일회성 테스트 작성,
   또는 앱의 "Import DOCX" 버튼 사용
3. 확인: heading/table 구조 유지, 예외 없음

## 3. 실패 시

Word가 파일을 거부하면 **원인 XML 조각을 반드시 S3-REPORT.md에 남길 것**:
재저장 전 파일을 unzip → Word가 지적하는 파트를 xmllint로 검사 →
해당 조각을 리포트의 "Word 거부 사례" 절에 첨부.
