package jp.jig.sabera.hello.arrow3d

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3次元ベクトル。新しい依存を増やしたくないので、必要な演算だけをここに持つ
 * （行列ライブラリを足すほどの規模ではない）。
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float): Vec3 = Vec3(x * s, y * s, z * s)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    fun length(): Float = sqrt(dot(this))

    /** 長さ0のベクトルを正規化しようとすると NaN になるので、その場合は Z 軸に逃がす */
    fun normalized(): Vec3 {
        val len = length()
        return if (len < 1e-6f) Vec3(0f, 0f, 1f) else times(1f / len)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

/**
 * 3x3 の回転行列。列優先・行優先を気にしなくていいように、常に
 * 「[times] にベクトルを渡すと回転後のベクトルが返る」という向きだけを約束する。
 *
 * 平行移動は持たない。[jp.jig.sabera.hello.arrow3d.ArrowMesh] はモデル原点を中心に
 * 置いてあるので、姿勢は回転だけで表現できる。
 */
data class Mat3(
    val m00: Float, val m01: Float, val m02: Float,
    val m10: Float, val m11: Float, val m12: Float,
    val m20: Float, val m21: Float, val m22: Float,
) {
    fun times(v: Vec3): Vec3 = Vec3(
        m00 * v.x + m01 * v.y + m02 * v.z,
        m10 * v.x + m11 * v.y + m12 * v.z,
        m20 * v.x + m21 * v.y + m22 * v.z,
    )

    /** 行列の合成。`a.times(b).times(v)` は `a.times(b.times(v))` と同じになる */
    fun times(o: Mat3): Mat3 = Mat3(
        m00 * o.m00 + m01 * o.m10 + m02 * o.m20,
        m00 * o.m01 + m01 * o.m11 + m02 * o.m21,
        m00 * o.m02 + m01 * o.m12 + m02 * o.m22,

        m10 * o.m00 + m11 * o.m10 + m12 * o.m20,
        m10 * o.m01 + m11 * o.m11 + m12 * o.m21,
        m10 * o.m02 + m11 * o.m12 + m12 * o.m22,

        m20 * o.m00 + m21 * o.m10 + m22 * o.m20,
        m20 * o.m01 + m21 * o.m11 + m22 * o.m21,
        m20 * o.m02 + m21 * o.m12 + m22 * o.m22,
    )

    companion object {
        val IDENTITY = Mat3(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )

        /**
         * X軸まわりの回転。[ArrowPose] ではピッチに使う。
         * `(0,0,1)` を回すと `(0, -sinθ, cosθ)` になる向き。
         */
        fun rotationX(radians: Float): Mat3 {
            val c = cos(radians)
            val s = sin(radians)
            return Mat3(
                1f, 0f, 0f,
                0f, c, -s,
                0f, s, c,
            )
        }

        /**
         * Y軸（鉛直軸）まわりの回転。[ArrowPose] では方位（ヨー）に使う。
         * `(0,0,1)`（モデルの正面）を回すと `(sinθ, 0, cosθ)` になる向き。
         * θ を方位角そのもの（北=0、東=90 で正）に取ると、真上から見て
         * 時計回りに振れる、実際の方位磁針と同じ向きになる。
         */
        fun rotationY(radians: Float): Mat3 {
            val c = cos(radians)
            val s = sin(radians)
            return Mat3(
                c, 0f, s,
                0f, 1f, 0f,
                -s, 0f, c,
            )
        }

        /** Z軸（矢印自身が指す軸）まわりの回転。[ArrowPose] ではロールに使う */
        fun rotationZ(radians: Float): Mat3 {
            val c = cos(radians)
            val s = sin(radians)
            return Mat3(
                c, -s, 0f,
                s, c, 0f,
                0f, 0f, 1f,
            )
        }
    }
}
