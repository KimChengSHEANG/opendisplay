struct RateAction {
    var bitrate: Int?
    var forceKeyframe: Bool
    var preferTcpNextSession: Bool
}

final class VideoRateController {
    private var bitrate: Int

    init(initialBitrate: Int) {
        self.bitrate = initialBitrate
    }

    func next(lossPct: Double, jitterMs: Double, nackRate: Double) -> RateAction {
        if lossPct >= 20 {
            return RateAction(bitrate: nil, forceKeyframe: true, preferTcpNextSession: true)
        }
        if lossPct > 5 || jitterMs > 25 {
            bitrate = max(2_000_000, bitrate - 4_000_000)
            return RateAction(bitrate: bitrate, forceKeyframe: false, preferTcpNextSession: false)
        }
        if nackRate > 0.10 {
            return RateAction(bitrate: nil, forceKeyframe: true, preferTcpNextSession: false)
        }
        if lossPct < 1 && jitterMs < 10 {
            bitrate = min(bitrate + 1_000_000, 40_000_000)
            return RateAction(bitrate: bitrate, forceKeyframe: false, preferTcpNextSession: false)
        }
        return RateAction(bitrate: nil, forceKeyframe: false, preferTcpNextSession: false)
    }
}
