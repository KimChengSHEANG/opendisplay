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
- FEC: ~20% Reed–Solomon parity shards per frame (Sunshine default).
- NACK: `{"type":"nack","streamId":N,"missing":[seq,...]}` over TCP; Mac retransmits from cache.
- Late/incomplete frames are dropped; receiver requests `kf` when reference chain breaks.
- `qos`: `{"type":"qos","lossPct":…,"jitterMs":…,"nackRate":…,"lateFrames":…}` ~2Hz for AIMD bitrate.
