struct RateAction {
    var bitrate: Int?
    var forceKeyframe: Bool
    var preferTcpNextSession: Bool
}

/// AIMD bitrate from receiver qos — start near panel budget, cut gently, climb fast
/// (Sunshine-style: keep picture sharp without cliffing on mild WiFi loss).
final class VideoRateController {
    private var bitrate: Int

    /// Chromebook panels want high bitrate; 8Mbps felt soft vs TCP's ~36Mbps.
    static let udpInitialBitrate = 24_000_000

    init(initialBitrate: Int) {
        self.bitrate = max(2_000_000, initialBitrate)
    }

    func next(lossPct: Double, jitterMs: Double, incompleteRate: Double) -> RateAction {
        if lossPct >= 20 {
            return RateAction(bitrate: nil, forceKeyframe: true, preferTcpNextSession: true)
        }
        if lossPct > 8 || jitterMs > 30 {
            bitrate = max(4_000_000, bitrate - 2_000_000)
            return RateAction(bitrate: bitrate, forceKeyframe: false, preferTcpNextSession: false)
        }
        if incompleteRate > 0.15 {
            return RateAction(bitrate: nil, forceKeyframe: true, preferTcpNextSession: false)
        }
        if lossPct < 2 && jitterMs < 15 {
            bitrate = min(bitrate + 2_000_000, 40_000_000)
            return RateAction(bitrate: bitrate, forceKeyframe: false, preferTcpNextSession: false)
        }
        return RateAction(bitrate: nil, forceKeyframe: false, preferTcpNextSession: false)
    }
}
