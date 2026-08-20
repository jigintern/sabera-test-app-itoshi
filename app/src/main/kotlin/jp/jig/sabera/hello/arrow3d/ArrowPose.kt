package jp.jig.sabera.hello.arrow3d

import kotlin.math.PI

private const val DEG_TO_RAD = (PI / 180.0).toFloat()

/**
 * 入力（北 or 六軸）から求めた矢印の姿勢。中身は回転行列1つだけ。
 *
 * **ブランチ2・3はここを呼ぶだけにする。** 北ブランチと六軸ブランチで数式が
 * 分かれてしまうと、どちらかを直したときにもう片方が古いままになりやすい。
 * 角度の数式はこのファイル1箇所に集めてある。
 */
data class ArrowPose(val rotation: Mat3) {
    companion object {
        /**
         * 北ブランチ向け。絶対方位（0..360、北=0、東=90）から姿勢を作る。
         *
         * ピッチ・ロールは常に0として [fromAttitude] を呼ぶだけの薄いラッパー。
         * 「向き＝ズレの方向ではなく矢印自体が姿勢を表す」六軸側と違い、北は
         * 水平面内の方位だけを表すので、ヨー以外の自由度を持たせない。
         */
        fun fromBearing(bearingDegrees: Float): ArrowPose =
            fromAttitude(pitchDegrees = 0f, yawDegrees = bearingDegrees, rollDegrees = 0f)

        /**
         * 六軸ブランチ向け。基準姿勢からのズレ（ピッチ・ヨー・ロール、単位は度）から
         * 矢印そのものを傾ける。
         *
         * 合成順は Yaw・Pitch・Roll（航空機の姿勢表現でよく使われる並び）で、
         * `Ry * (Rx * (Rz * v))` の順に適用する。つまりモデルはまず自身の軸で
         * ロールし、次に機首上げ下げ（ピッチ）、最後に水平方向の向き（ヨー）を
         * 変える。他の順でも「大体傾く」見た目にはなるが、複数軸を同時に動かした
         * ときの挙動が変わるので、実機で確認できるまではこの並びを既定とする。
         *
         * [pitchDegrees] は [app.jigglass.glass.CommandManager.ImuData.pitchDegrees]
         * と同じ規約（上向きが負）で渡すこと。[Mat3.rotationX] がそのまま
         * 「θ が負なら先端が +Y（上）に振れる」向きになっているので符号反転は不要。
         */
        fun fromAttitude(pitchDegrees: Float, yawDegrees: Float, rollDegrees: Float): ArrowPose {
            val roll = Mat3.rotationZ(rollDegrees * DEG_TO_RAD)
            val pitch = Mat3.rotationX(pitchDegrees * DEG_TO_RAD)
            val yaw = Mat3.rotationY(yawDegrees * DEG_TO_RAD)
            return ArrowPose(yaw.times(pitch).times(roll))
        }
    }
}
