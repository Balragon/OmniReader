#!/bin/zsh
# mdvault 에뮬레이터 실행 + 최신 release 빌드 설치 + 앱 시작
# (Claude Code 세션은 GUI 창을 못 띄우므로 이 스크립트를 open으로 실행)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
cd "$HOME/Documents/Coding/mdvault" || exit 1

if ! adb devices | grep -q "emulator.*device"; then
    echo "에뮬레이터 부팅 중…"
    nohup "$ANDROID_HOME/emulator/emulator" -avd mdvault-api34 >/dev/null 2>&1 &
    disown
    adb wait-for-device
    while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        sleep 2
    done
fi

echo "최신 release 빌드 설치…"
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n dev.gold.mdvault/.ui.MainActivity
echo ""
echo "✅ mdvault 실행됨 — 이 창은 닫아도 됩니다 (에뮬레이터는 유지)"
echo "undo 테스트: 앱에서 Spike → S5 Editor → 한 문장 타이핑 → 잠깐 멈춤 → Undo"
