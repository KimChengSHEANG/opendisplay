# TCP baseline checklist (Phase 0)

Use the same scenario before/after UDP so A/B numbers are comparable.

## Setup

| Knob | Value |
|---|---|
| Device | Chromebook (preferred) + one Android phone |
| Transport | WiFi Bonjour (not `adb forward`) |
| Sharpness | Best |
| Frame rate | 60 |
| Resolution | Standard (1:1 panel) |
| Overlay | Settings → Performance overlay ON |

## Procedure

1. Connect Mac → device; wait until overlay shows stable FPS (~5s).
2. Drag a window on the extended display for ~20s (steady motion).
3. Record one steady window (ignore first 3s after connect):

| Metric | Where | Record |
|---|---|---|
| latency (e2e P50) | overlay / PHONE-STATS `e2e50` | |
| p95 (e2e P95) | overlay / `e2e95` | |
| decode | overlay `decode` | |
| FPS | overlay | |
| stalls | overlay | |
| enc↓ / net↓ | overlay | |
| transport | badge (WiFi) | |

4. Repeat once after a cold reconnect (force-stop app → reopen).

## Keep-UDP gate (Phase 6)

Vs this TCP baseline on the **same Chromebook WiFi**:

- e2e P50 improves or within ~10%
- e2e P95 and/or stalls clearly better under drag
- no green/black / cold-connect regressions

If not met: leave `auto` → TCP; keep UDP as an explicit setting.

## Notes

Physical device numbers are filled in during manual A/B after Phases 1–3.
On-device capture is out of band for CI; unit tests cover shard/FEC maths
(`UdpVideoFecTest`: round-trip + single-shard recovery).

## A/B table (fill on device)

| Path | Device | e2e50 | e2e95 | stalls | notes |
|---|---|---|---|---|---|
| TCP baseline | Chromebook WiFi | SKIPPED (no device) | SKIPPED (no device) | SKIPPED (no device) | Tasks 1–4 code ready for on-device fill (Phase 0) |
| UDP+FEC paced | Chromebook WiFi | SKIPPED (no device) | SKIPPED (no device) | SKIPPED (no device) | Explicit `videoTransport udp`; `udpAutoEnabled` stays false until keep-UDP gate passes on device |

`auto` default: prefer UDP on Android/Chromebook WiFi when the keep-UDP gate
passes; otherwise TCP. Toggle: `defaults write … videoTransport tcp|udp|auto`.
