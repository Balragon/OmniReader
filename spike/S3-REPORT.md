# S3 Spike Report — 수제 OOXML DOCX Writer

날짜: 2026-07-03 · 외부 의존성 0개 (flexmark AST + java.util.zip만 사용)

## 판정: ✅ 성공

- fixtures/md 10개 전체 export → 모든 XML 파트 well-formed (DocumentBuilder 검증)
- **LibreOffice headless가 우리 DOCX를 정상 파싱·PDF 변환** — 제목 위계,
  굵게/기울임, 중첩 불릿(◦)/순번(startOverride 재시작), 표(테두리·헤더 굵게·
  열 정렬), 하이퍼링크, 이미지 모두 올바르게 렌더링됨
- **Mammoth 재import round-trip 자동 테스트 통과**: h1/h2, strong/em, ul/ol,
  table, href가 전부 보존 (SimpleOoxmlDocxExportEngineTest)
- Word가 파일을 거부한 사례: 현재까지 없음 (Word/Google Docs 수동 검증은
  S3-MANUAL-VERIFICATION.md 절차로 잔여)

## 구성요소

| 파일 | 역할 |
|---|---|
| OoxmlWriter | XML escape + 불법 제어문자 strip, 순수 Kotlin |
| ImageDimensionReader | PNG IHDR / JPEG SOF 파싱 (BitmapFactory 불사용), px→EMU(×9525) |
| SimpleOoxmlDocxExportEngine | flexmark AST → 8개 zip 파트 생성 |

생성 파트: [Content_Types].xml, _rels/.rels, word/document.xml, word/styles.xml,
word/numbering.xml, word/_rels/document.xml.rels, word/media/*, docProps/core.xml

## 설계 결정

- **모든 `<w:t>`에 `xml:space="preserve"`** — "word **bold** word" run 분절 간
  공백 보존을 테스트로 강제 (w:t 개수 == preserve 개수 assert)
- 페이지: A4 + 1인치 여백. 본문 폭 9026 twips → 이미지 최대 폭 5,731,510 EMU,
  초과 시 비율 유지 축소 (2000×500px 가짜 PNG로 테스트)
- ordered list마다 `w:num` 인스턴스 분리 + `startOverride` — 연속된 별개 목록이
  번호를 이어가는 버그 방지
- 지원 밖 문법(코드블록/blockquote/tasklist/HTML/heading4+)은
  `ConversionWarning.UnsupportedFeature` + plain text 강등, 예외 없음
- 제어문자는 OoxmlWriter가 strip (raw 0x08/0x0B 포함 markdown 테스트 통과)

## 관찰 사항

- `***굵은기울임***` 직후에 한글이 붙으면 flexmark가 emphasis로 파싱하지 않고
  literal로 남김 — writer 문제가 아니라 CommonMark 구분자 규칙. 실사용에서
  발견되면 P1에서 다룰 것
- 수동 검증 샘플은 `./gradlew test` 실행 시
  `app/build/outputs/spike/s3-sample.docx`로 갱신됨 (S3SampleDocxGenerator)

## 게이트 판정

S1(성공) + S3(성공) → **Phase 2 착수 가능. 인터페이스 확정:**
`DocxImportEngine`/`ImageSink`/`HtmlImportResult`, `DocxExportEngine`/`AssetResolver`,
`MarkdownEngine`, `ConversionWarning`, `EditorPort`
