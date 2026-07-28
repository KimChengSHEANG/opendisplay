# Why Sunshine/Moonlight Works So Well Over UDP

Sunshine/Moonlight is fast and stable over UDP because it is not using "naive UDP". It adds the transport and media-control machinery needed to make realtime display streaming work well on imperfect WiFi.

## Big Reasons

- Video frames are treated as disposable when needed. For interactive streaming, a late frame is often worse than a lost frame, so the stack prefers forward progress over perfect delivery.
- Reliability is added selectively instead of globally. Sequence numbers, timestamps, frame boundaries, loss detection, Reed–Solomon FEC, and fast IDR/keyframe recovery help without forcing the whole stream to stall. Sunshine does **not** rely on classic packet NACK retransmit for video; FEC absorbs small losses, then the client requests encoder-level recovery (IDR / RFI) over the control channel.
- Control traffic is kept separate from bulk video traffic. Input, keepalives, session control, and wake/sleep signals avoid getting stuck behind queued video data.
- Bitrate and packet pacing are tuned for real networks. Good pacing and congestion handling reduce burstiness and make home WiFi behave much better.
- The encoder is configured for low latency. Hardware encode, small encoder buffers, minimal frame reordering, and fast keyframe recovery reduce both latency and recovery time.
- A small jitter buffer smooths WiFi variation. This absorbs short-term packet timing noise without adding a large interactive delay.
- Decoder and renderer paths are highly optimized. Hardware decode, direct rendering, frame scheduling, and strong recovery behavior matter as much as the wire protocol.
- The stack has been hardened against real-world edge cases. Packet loss, reordering, router quirks, roaming, wake/sleep, bitrate cliffs, and device-specific decoder issues are all handled deliberately.

## Practical Takeaway

UDP alone is not what makes Sunshine/Moonlight good. The advantage comes from the full low-latency transport design around UDP.

For OpenDisplay, switching from TCP to plain UDP without adding the same surrounding machinery would likely reduce stability rather than improve it.
