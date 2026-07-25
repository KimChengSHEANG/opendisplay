# Build the OpenDisplay apps. Regenerates the Xcode project from project.yml
# first (generate.sh reads DEVELOPMENT_TEAM from .env), then builds a scheme.
#
#   make mac              # build the macOS sender app
#   make run              # build + launch Mac app
#   make ios              # build iOS receiver (simulator SDK, unsigned)
#   make ios-device       # build iOS receiver for physical devices (signed)
#   make ios-run          # build + install + launch on a connected iOS device
#   make android          # build Android debug APK
#   make android-run      # build + install + launch on a connected Android device
#   make all              # mac + ios (simulator) + android
#   make clean
#
# Device selection: with multiple devices, prompts 1 / 2 / …. Skip the prompt
# with ANDROID_SERIAL=<serial> or IOS_DEVICE=<CoreDevice-id|UDID>.

CONFIG      ?= Debug
DD          = build
APP         = $(DD)/Build/Products/$(CONFIG)/OpenDisplay.app
IOS_APP     = $(DD)/Build/Products/$(CONFIG)-iphoneos/OpenSidecariOS.app
IOS_BUNDLE  = com.peetzweg.opensidecar.ios
ANDROID_DIR = Android
ANDROID_APK = $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk
ANDROID_PKG = com.peetzweg.opendisplay

.PHONY: all mac ios ios-device ios-run android android-run run clean generate

all: mac ios android

generate:
	./generate.sh

mac: generate
	xcodebuild -project OpenSidecar.xcodeproj -scheme OpenSidecarMac \
		-configuration $(CONFIG) -derivedDataPath $(DD) build

ios: generate
	xcodebuild -project OpenSidecar.xcodeproj -scheme OpenSidecariOS \
		-configuration $(CONFIG) -sdk iphonesimulator \
		-destination 'generic/platform=iOS Simulator' \
		-derivedDataPath $(DD) CODE_SIGNING_ALLOWED=NO build

# Physical iPhone/iPad — needs DEVELOPMENT_TEAM in .env (see generate.sh).
ios-device: generate
	xcodebuild -project OpenSidecar.xcodeproj -scheme OpenSidecariOS \
		-configuration $(CONFIG) -sdk iphoneos \
		-destination 'generic/platform=iOS' \
		-derivedDataPath $(DD) -allowProvisioningUpdates build

android:
	cd $(ANDROID_DIR) && ./gradlew :app:assembleDebug

# Kill any live instance so we don't reuse a stale process, pause briefly so
# WindowServer can release the previous virtual-display serial, then open.
run: mac
	@test -d "$(APP)" || (echo "Mac app missing at $(APP)"; exit 1)
	-killall OpenDisplay 2>/dev/null
	@sleep 0.3
	open "$(APP)"
	@echo "OpenDisplay running — logs at /tmp/opensidecar-mac.log."

# Build APK → pick adb device → install → launch.
android-run: android
	@test -f "$(ANDROID_APK)" || (echo "APK missing at $(ANDROID_APK)"; exit 1)
	@serial=$$(./scripts/pick-adb-device.sh); \
	echo "Installing on $$serial..."; \
	adb -s "$$serial" install -r "$(ANDROID_APK)"; \
	adb -s "$$serial" shell am force-stop "$(ANDROID_PKG)" >/dev/null 2>&1 || true; \
	adb -s "$$serial" shell am start -n "$(ANDROID_PKG)/.MainActivity"; \
	echo "OpenDisplay Android running on $$serial."

# Build for device → pick iPhone/iPad → install → launch.
ios-run: ios-device
	@test -d "$(IOS_APP)" || (echo "iOS app missing at $(IOS_APP)"; exit 1)
	@device=$$(./scripts/pick-ios-device.sh); \
	./scripts/ios-install-run.sh "$$device" "$(IOS_APP)" "$(IOS_BUNDLE)"; \
	echo "OpenDisplay iOS running on $$device."

clean:
	rm -rf $(DD)
