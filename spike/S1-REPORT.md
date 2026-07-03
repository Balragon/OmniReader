# S1 Spike Report — java-mammoth Android 실사용 판정

날짜: 2026-07-03 · mammoth 1.9.0 · AGP 8.5.2 / Gradle 8.9 / JDK 17

## 판정: ✅ 성공 (조건 1건 잔여: 실기기 시간 측정)

- R8(minify) release 빌드 통과, **keep rule 0개 필요**
- fixture 10개 전체 변환 **crash 0건** (sanitizer 도입 후)
- Mammoth.js WebView sandbox 대안 검토 불필요 → **Phase 2 인터페이스 확정 가능**

## 1. R8 / release 빌드

`./gradlew assembleRelease` (isMinifyEnabled=true, proguard-android-optimize):

- missing class 오류 0건, `-dontwarn`/keep rule 추가 없이 통과
- mapping.txt 기준 `org.zwobble.mammoth` 클래스 **147개 유지** — MainActivity의
  spike 하네스가 `importDocx`를 실제 호출하므로 도달 가능성이 보장된 유의미한 결과
- APK 2.08MB (debug 27MB 대비). release는 debug 서명 적용(개인용 측정 목적)

## 2. Fixture 변환 결과 (순수 JUnit, MammothDocxImportEngineTest)

| fixture | 결과 |
|---|---|
| simple-korean, formatting, table-basic, lists, links, llm-generated, large | ✅ 정상 변환 |
| images.docx (11MB) | ✅ asset 5개 파일 추출, HTML < 1MB, data: URI 0건 |
| table-merged.docx | ✅ 예외 없이 `<table>` 생성 (Mammoth이 병합 셀 자체 처리) |
| control-chars.docx | ⚠️→✅ 아래 참조 |

공통 검증: HTML 내 `src="data:` 패턴 0건(정규식), 이미지 파일명
`media/<sha256 12자리>-<3자리 순번>.<ext>` 규칙 준수, sink 저장 파일 크기 일치.

## 3. 발견된 지뢰와 처치

**Mammoth은 XML 1.0 불법 제어문자에서 죽는다.**
control-chars.docx(raw `0x08` 포함)에서 내부 SAX 파서가
`RuntimeException: SAXParseException ... 유니코드: 0x8` 를 던짐
(`org.zwobble.mammoth.internal.xml.parsing.SimpleSax.parseInputSource`).

처치: `DocxXmlSanitizer` — import 전에 zip의 `.xml`/`.rels` 파트에서
불법 바이트(0x00-0x08, 0x0B, 0x0C, 0x0E-0x1F)를 제거하고
`ConversionWarning.IllegalXmlCharactersStripped(count)`로 보고.
UTF-8에서 해당 값은 항상 단일 바이트이므로 바이트 단위 필터가 안전.

트레이드오프: sanitizer가 문서 전체를 메모리에 버퍼링(11MB fixture 기준 문제
없음). 초대형 문서가 실사용에서 나타나면 캐시 파일 스트리밍으로 전환.

## 4. 확정된 인터페이스 (Phase 2 기준)

```kotlin
interface DocxImportEngine {
    fun importDocx(input: InputStream, imageSink: ImageSink): HtmlImportResult
}
fun interface ImageSink { fun store(relativePath: String, contentType: String, bytes: ByteArray) }
data class HtmlImportResult(html, warnings: List<ConversionWarning>, extractedAssets: List<ExtractedAsset>)
```

- 이미지는 base64 삽입 없이 sink로 즉시 externalize, HTML에는 상대경로만
- Mammoth 문자열 warning → `ConversionWarning.UnsupportedFeature`로 매핑

## 5. 잔여: 실기기 측정 절차 (Galaxy 연결 후)

1. `adb install app/build/outputs/apk/release/app-release.apk`
2. 앱 실행 → "Import DOCX" → fixtures/docx/simple-korean.docx, images.docx 선택
3. 화면에 표시되는 elapsed ms 기록 → 이 리포트에 추기
