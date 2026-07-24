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

run: mac
	./run.sh

clean:
	rm -rf $(DD)
