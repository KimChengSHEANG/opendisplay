# Android + Chromebook receiver (iOS parity)

**Date:** 2026-07-24  
**Status:** Approved for implementation planning  
**Approach:** Mirror iOS receiver in native Kotlin + Jetpack Compose; extend the same OpenDisplay Mac app for WiFi + USB Android peers

## Problem

OpenDisplay today is Mac sender + iOS receiver only. Users with Android phones/tablets or Chromebooks cannot act as a second display. The product goal is **full iOS feature parity** on Android (including Chromebook via the Android app runtime), including **both WiFi and USB** before calling v1 done.

## Goals

1. Native **Kotlin + Jetpack Compose** Android receiver APK (phones, tablets, Chromebooks).
2. Speak the **same wire protocol** as iOS (listen `:9000`, length-prefixed Annex B H.264, JSON control).
3. Integrate with the **same Mac OpenDisplay app** (device list alongside iPhone/iPad).
4. **WiFi** via mDNS `_opensidecar._tcp` and **USB** via ADB forward TCP (phones/tablets).
5. Feature parity with iOS before v1: stream, discovery, handshake, touch/scroll, orientation, sleep/`hostSleeping`, settings, version gate, USB.

## Non-goals (v1)

- Rewrite or replace the iOS app
- Kotlin Multiplatform shared core with iOS
- Separate Mac helper binary for Android
- Chromebook **USB** cable path (unreliable into ARC); Chromebook v1 = WiFi/Ethernet
- Apple Pencil analogue, Play Store launch requirements as a blocker (sideload/GitHub OK initially)
- Changing Mac `CGVirtualDisplay` / capture stack beyond peer discovery and transport

## Architecture

```
MAC (OpenDisplay, extended)              ANDROID / CHROMEBOOK (new APK)
CGVirtualDisplay → capture → H.264
        │ TCP [4-byte BE len][Annex B] ═══→  ServerSocket :9000
        │                                    MediaCodec → Surface (fullscreen)
   ← JSON control (hello, touch, scroll,     ControlChannel
      sleeping, closing, welcome, …)
WiFi: NWBrowser _opensidecar._tcp  ←──→  NsdManager advertise
USB:  adb forward tcp:9000 + dial 127.0.0.1:9000  (phones/tablets)
```

Mental model unchanged: **device listens, Mac connects.**

## Feature parity checklist (v1 exit criteria)

| Area | Behavior |
|---|---|
| Stream | TCP listen `:9000`, framed H.264, hardware decode → fullscreen |
| Discovery | mDNS `_opensidecar._tcp` + TXT `id`, `pv` |
| Hello / handshake | Panel size, install id, device kind, `pv`; handle `welcome` / `updateRequired` |
| Input | Touch → click/drag; two-finger scroll |
| Orientation | Portrait/landscape; Mac rebuilds virtual display |
| Sleep | Local lock → `sleeping`; quit → `closing`; Mac `hostSleeping` → black UI, brightness 0, allow Auto-Lock, **keep listening** for Mac wake |
| Settings | Device name, analytics overlay, prefs aligned with iOS where applicable |
| Version gate | Remote config + peer signals (same product rules as COMPATIBILITY.md) |
| USB | Cable path for Android phones/tablets via ADB forward |

## Transports

### WiFi

- Android advertises `_opensidecar._tcp` on port 9000 (NSD).
- Existing Mac Bonjour browser discovers peers; no protocol change.
- Primary path for **Chromebook**.

### USB (phones/tablets) — required for v1

Apple `usbmuxd` does not apply. Use **ADB forward TCP** (the receiver listens on the device; forward routes Mac localhost to device localhost):

1. App listens on `:9000`.
2. Mac detects authorized ADB device (`adb devices` / watcher).
3. Mac runs `adb -s <serial> forward tcp:9000 tcp:9000`.
4. Mac dials `127.0.0.1:9000` (existing TCP sender path).
5. Detach → remove forward + end session (parity with usbmux unplug).

**Mac dependency:** Android `adb` (platform-tools) — bundle a pinned version or detect Homebrew/`PATH` and prompt if missing. Device needs USB debugging authorized once.

### USB (Chromebook)

Out of scope for v1. Document clearly: Chromebook = WiFi/Ethernet only for cable-equivalent use.

## Mac app integration

- Single OpenDisplay Mac app lists:
  - iOS USB (usbmux)
  - Android USB (ADB serial)
  - WiFi (Bonjour) for both ecosystems
- `hello` device kind: e.g. `"Android"` / `"Chromebook"` for labels and display serial hashing.
- Prefer USB when the same install `id` is visible on both transports (same policy as iOS).
- Host sleep / `hostSleeping` / wake reconnect: unchanged wire behavior.

## Android app structure

New `Android/` Gradle project:

| Component | Responsibility |
|---|---|
| `ReceiverSession` | Listen, framing, session lifecycle |
| `VideoDecoder` | MediaCodec → Surface |
| `ControlChannel` | JSON control messages |
| `DiscoveryAdvertiser` | NSD advertise |
| `InputForwarder` | MotionEvent → touch/scroll |
| `HostSleepController` | `hostSleeping` blank + idle/lock |
| `VersionGate` | Force/nag update + peer signals |
| `ui/` | Compose Idle / Streaming / Settings / Update gate |

**Wire constants:** Hand-sync Kotlin copies of `WireProtocol` / `WireMessage` with `Shared/Protocol.swift`; note in COMPATIBILITY.md. No code generation in v1.

**Lifecycle:** Foreground service while streaming. `FLAG_KEEP_SCREEN_ON` while connected; clear on disconnect / host sleep so the device can lock.

**Permissions:** `INTERNET`; notifications for foreground service; NSD as required by API level. ADB is Mac-side only.

## Delivery sequencing (still one v1 bar)

Slices stay mergeable, but **v1 is not declared until the parity checklist + USB phones/tablets are done**:

1. Android WiFi MVP (listen, decode, hello, touch/scroll, NSD)
2. Mac Bonjour + UI recognition of Android peers
3. Parity features (sleep, hostSleeping, settings, version gate, orientation)
4. Mac ADB watcher + forward + USB session policy
5. Chromebook validation (WiFi), docs, packaging

## Testing (manual)

- Android phone WiFi stream + touch/scroll + rotate
- Android phone USB (adb forward) plug/unplug
- Chromebook WiFi stream
- Local lock → Mac tears down + wake reconnect
- Mac display sleep/lock → phone black + Auto-Lock allowed + Mac wake reconnect
- Version gate / `updateRequired` path
- Missing `adb` Mac UX

## Open decisions (resolved in brainstorming)

- Scope: full iOS parity for v1 (not MVP-only)
- USB: required for v1 (phones/tablets); Chromebook USB out of scope
- Stack: native Kotlin + Compose
- Mac: same app, not a separate helper
