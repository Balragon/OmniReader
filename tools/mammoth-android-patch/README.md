# mammoth Android SAX 호환 패치

## 왜 필요한가

Android libcore는 `SAXParserFactory.newInstance()`가 **하드코딩**으로
`org.apache.harmony.xml.parsers.SAXParserFactoryImpl`을 반환하고
(시스템 프로퍼티 무시 — "instantiate the class directly rather than using
reflection"), 이 Expat 기반 구현은 mammoth가 XXE 방어용으로 켜는

- `http://apache.org/xml/features/disallow-doctype-decl`
- `http://javax.xml.XMLConstants/feature/secure-processing`

feature를 `SAXNotRecognizedException`으로 거부한다. 그 결과 **기기에서 모든
DOCX import가 실패**한다 (JVM Xerces에서는 정상 → JUnit으로는 재현 불가.
2026-07-04 에뮬레이터 스모크 테스트에서 발견).

## 패치 내용

`SimpleSax.java`의 `setFeature` 3건을 best-effort(`trySetFeature`)로 변경.
그 외 코드는 upstream(1.9.0)과 동일. 잃어버리는 DOCTYPE/XXE 방어는
`DocxXmlSanitizer`가 DOCTYPE 선언을 입력에서 제거하는 것으로 대체된다
(DocxXmlSanitizerTest가 강제).

## 재생성 절차 (mammoth 버전 올릴 때)

```bash
cd tools/mammoth-android-patch
# 1. 새 버전 sources.jar에서 SimpleSax.java를 받아 upstream 변경을 diff 확인 후
#    trySetFeature 패치를 다시 적용
# 2. 컴파일 & jar 갱신 (mammoth-X.Y.Z.jar는 gradle cache 또는 Maven Central)
javac -source 8 -target 8 -cp mammoth-X.Y.Z.jar \
    org/zwobble/mammoth/internal/xml/parsing/SimpleSax.java
cp mammoth-X.Y.Z.jar ../../app/libs/mammoth-X.Y.Z-android.jar
jar uf ../../app/libs/mammoth-X.Y.Z-android.jar \
    org/zwobble/mammoth/internal/xml/parsing/SimpleSax.class \
    'org/zwobble/mammoth/internal/xml/parsing/SimpleSax$1.class'
# 3. app/build.gradle.kts의 files(...) 경로 갱신, 구버전 jar 삭제
# 4. ./gradlew test + 에뮬레이터에서 DOCX import 확인
```
