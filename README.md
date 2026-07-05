# OmniReader

가볍고 **완전 오프라인**인 안드로이드 문서 뷰어. "내 파일" 등에서 문서를 탭하면
바로 열리는 연결 앱입니다. 계정·광고·네트워크 접속 없이, 열어서 읽는 것만 합니다.

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_foreground.png" width="140" alt="OmniReader 아이콘">
</p>

## 지원 형식

| 형식 | 동작 |
|------|------|
| Markdown (`.md`) | 서식 렌더링 |
| 텍스트 (`.txt`) | 그대로 표시 |
| HTML (`.html`) | JavaScript·네트워크 차단 상태로 표시(오프라인) |
| PDF | 내장 렌더러로 페이지 스크롤 + 핀치 줌 |
| 이미지 (`.png .jpg .webp .gif`) | 화면 맞춤 표시, 핀치 줌, EXIF 회전, GIF 애니메이션 |
| Word (`.docx`) | 즉석에서 Markdown으로 변환해 표시, 원할 때 "MD 저장"으로 내보내기 |

## 기능

- 텍스트 문서 글자 크기 조절(Aa), 문서별 읽기 위치 기억
- 최근 파일 목록(앱에서 "파일 열기"로 연 문서)
- 갤러리처럼 탭하면 상태바/내비바가 숨겨지는 몰입 보기(이미지·PDF)
- **오프라인 전용**: 인터넷 권한 자체가 없습니다. 문서를 밖으로 보내지 않습니다.
- 원본 파일을 수정하지 않습니다(뷰어 전용). DOCX "MD 저장"만 새 파일을 만듭니다.

## 설치 (안드로이드)

Play 스토어가 아닌 **직접 설치(사이드로드)** 방식입니다.

1. [최신 릴리스](https://github.com/Balragon/OmniReader/releases/latest)에서 `OmniReader-x.y.z.apk`를 안드로이드 기기로 내려받습니다.
2. 처음이라면 "출처를 알 수 없는 앱 설치"를 허용해야 합니다
   (설정 → 앱 → 특별한 앱 접근 → 알 수 없는 앱 설치 → 사용하는 브라우저/파일 앱 허용).
3. 내려받은 APK를 탭해 설치합니다.
4. 이후 "내 파일" 등에서 문서를 탭하면 열기 앱 목록에 **OmniReader**가 나옵니다.

- 요구 사항: **안드로이드 10 (API 29) 이상**.
- 다른 곳에서 서명된 업데이트를 받으려면 같은 서명 키로 빌드된 APK여야 합니다
  (공식 릴리스는 항상 동일 키로 서명됩니다).

## 소스에서 빌드

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

릴리스 키(`keystore.properties`)가 없으면 자동으로 debug 서명으로 폴백해 빌드됩니다.
JDK 17이 필요합니다.

## 개발 문서

- 아키텍처·규칙: [CLAUDE.md](CLAUDE.md)
- 작업 로그·현재 상태·지뢰: [docs/HANDOFF.md](docs/HANDOFF.md)
- 로드맵: [docs/ROADMAP.md](docs/ROADMAP.md)

## 라이선스

[MIT License](LICENSE) — 자유롭게 사용·수정·재배포할 수 있습니다(저작권 표시 유지).
무상 제공되며 별도의 보증은 없습니다.
