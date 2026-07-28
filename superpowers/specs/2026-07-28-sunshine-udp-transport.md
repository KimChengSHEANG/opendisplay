# Sunshine UDP transport (protocol 3)

## Scope
Android/Chromebook WiFi video over UDP. TCP control on :9000. iOS + USB TCP unchanged.

## Negotiation
- Hello may include `"video":["tcp","udp"]` and `"udpPort":<u16>`.
- Mac sends `transportOffer` when it will try UDP: `{"type":"transportOffer","video":["udp","tcp"],"control":"tcp","udpPort":9001,"streamId":N,"fecPct":20}`.
- Receiver replies `transportSelected`: `{"type":"transportSelected","video":"udp"|"tcp","streamId":N}`.
- If either side selects `tcp`, video stays on the existing framed TCP path.

## Datagram (32-byte header + payload)
| Offset | Type | Field |
|---|---|---|
| 0 | u8 | version (=1 for UDP framing; independent of WireProtocol pv) |
| 1 | u8 | flags |
| 2 | u16 BE | seq |
| 4 | u32 BE | frameId |
| 8 | u16 BE | shardIndex |
| 10 | u16 BE | dataShardCount |
| 12 | u8 | fecPct |
| 13 | u8 | reserved |
| 14 | u16 BE | reserved2 |
| 16 | u64 BE | captureMs |
| 24 | u64 BE | sendMs |
| 32 | bytes | payload |

Flags: KEYFRAME 0x01, FEC_PARITY 0x02, START 0x04, END 0x08.

## Recovery

Sunshine/Moonlight model (not WebRTC):

- **FEC first:** ~20% Reed–Solomon parity shards per video frame (`fecPct`, Sunshine `fec_percentage` default). Receiver reconstructs missing data shards from parity when possible.
- **No packet NACK:** Do **not** send `{"type":"nack",...}` and do **not** cache/retransmit UDP datagrams. Late shards may still arrive within a short reorder window; after that the frame is abandoned.
- **IDR recovery:** When a frame is incomplete after the reorder window, late-dropped, or a decode error breaks the reference chain, receiver sends `{"type":"kf"}` over TCP. Mac forces an IDR.
- **Disposable frames:** Prefer forward progress over perfect delivery; incomplete/late frames are dropped.
- **`qos` (~2Hz):** `{"type":"qos","lossPct":…,"jitterMs":…,"incompleteRate":…,"lateFrames":…,"fecRecoveries":…}` for AIMD bitrate and TCP fallback. `incompleteRate` = incompleteFrames / frames in the window (0 if frames == 0).
