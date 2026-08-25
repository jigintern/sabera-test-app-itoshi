package jp.jig.sabera.hello.transport

/**
 * 「同時」タブが背景に流すストリームの組み合わせ。
 *
 * 前景の経路は同時に使えない。`showCanvas` は lock を取らないので、画像のような
 * 複数パケットの転送中に呼ぶとチャンクの間に割り込みグラス側の再組立を壊す
 * （[jp.jig.sabera.hello.glass.GlassSession.showCanvas] の KDoc、[ArrowTransport] の
 * KDoc と同じ理由）。だから「同時」の意味は**前景1経路 + 背景の受信ストリーム**でしかなく、
 * 前景を複数経路で並列に送ることは指せない。
 *
 * 背景に置けるのは IMU（[jp.jig.sabera.hello.glass.GlassSession.collectImuData]）と
 * マイク（[jp.jig.sabera.hello.glass.GlassSession.collectMicAudio]）の2つ。どちらも
 * 開始・停止が単発パケットで、複数パケットの転送（前景の画像経路）とは別の経路を通るため、
 * 前景と背景を同時に開けること自体は成立する。このタブが測りたいのは「同時に開けるか」
 * ではなく「同時に開けたときに、互いの品質がどれだけ落ちるか」である。
 */
enum class BackgroundLoad(val label: String, val note: String) {
    NONE(
        label = "なし",
        note = "背景ストリームを流さない。前景経路だけの基準値を取るための条件",
    ),
    IMU(
        label = "6DoF",
        note = "collectImuData を購読し続ける。imu/ImuStats.RateMeter でサンプル落ちを集計する",
    ),
    MIC(
        label = "マイク",
        note = "collectMicAudio を購読し続ける。audio/MicStats.MicMeter で毎秒バイト数を集計する" +
            "（期待値 32000B/秒）",
    ),
    BOTH(
        label = "6DoF+マイク",
        note = "両方を同時に購読する。帯域を奪い合う一番厳しい条件",
    ),
    ;

    /** IMU の背景購読を張るかどうか */
    val usesImu: Boolean get() = this == IMU || this == BOTH

    /** マイクの背景購読を張るかどうか */
    val usesMic: Boolean get() = this == MIC || this == BOTH
}
