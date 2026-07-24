# Build the OpenDisplay apps. Regenerates the Xcode project from project.yml
# first (generate.sh reads DEVELOPMENT_TEAM from .env), then builds a scheme.
#
#   make mac     # build the macOS sender app
#   make ios     # build the iOS receiver app (simulator SDK, unsigned)
#   make all     # both
#   make run     # build Mac app, kill any running instance, launch
#   make clean

CONFIG ?= Debug
DD      = build
APP     = $(DD)/Build/Products/$(CONFIG)/OpenDisplay.app

.PHONY: all mac ios run clean generate

all: mac ios

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

# Kill any live instance so we don't reuse a stale process, pause briefly so
# WindowServer can release the previous virtual-display serial, then open.
run: mac
	@test -d "$(APP)" || (echo "Mac app missing at $(APP)"; exit 1)
	-killall OpenDisplay 2>/dev/null
	@sleep 0.3
	open "$(APP)"
	@echo "OpenDisplay running — logs at /tmp/opensidecar-mac.log."

clean:
	rm -rf $(DD)
