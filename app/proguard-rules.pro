# flexmark은 확장/파서 팩토리의 의존성을 "클래스 정체성"으로 해석한다
# (DependencyResolver.resolveDependencies). R8 horizontal class merging이
# 서로 다른 팩토리 클래스를 병합하면 런타임에
# "Dependent class ... is duplicated" IllegalStateException으로 즉사한다.
# 이름 유지(-keepnames)로 병합·리네임을 금지한다 (shrinking은 허용).
# 발견 경위: 에뮬레이터 release 스모크 테스트, 2026-07-04. spike/S1-REPORT.md 참조.
-keepnames class com.vladsch.flexmark.** { *; }

# mammoth(java-mammoth) 자체는 keep rule 불필요 — S1 spike에서 검증됨.
# 단, Android SAX 호환을 위해 패치된 로컬 jar를 사용한다
# (app/libs/mammoth-1.9.0-android.jar — tools/mammoth-android-patch/README.md).
