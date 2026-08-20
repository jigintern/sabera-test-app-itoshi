package jp.jig.sabera.hello.arrow3d

import jp.jig.sabera.hello.image.GrayscaleImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 矢印メッシュをどう描くか。**なぜ独自のラスタライザを持つか**は
 * [ArrowMesh] の KDoc ではなくここに書く: 出力側の理由だから。
 *
 * 既存の [jp.jig.sabera.hello.flipbook.FlipbookScene.Shape] を使わない理由は2つ。
 *  - `Shape` は `flipbook` パッケージの `sealed interface` なので、別パッケージの
 *    `arrow3d` から実装を足せない
 *  - `covers(x, y): Boolean` は真偽値だけで階調を運べない。面を塗る方式
 *    ([FILLED]) は 0..7 の階調が要るので、この型では表現できない
 */
enum class ArrowStyle(val label: String, val note: String) {
    FILLED(
        label = "面塗り",
        note = "面の法線から0..7の階調を出す。塗りつぶすので、画素数が少ない経路でも" +
            "シルエットの形は残りやすい",
    ),
    WIREFRAME(
        label = "稜線",
        note = "辺だけを描く。塗りより情報量は少ないが、線がつぶれるかどうかで" +
            "経路の分解能をそのまま比較できる",
    ),
}

/**
 * [ArrowPose] から画を起こす。投影 → zバッファ → ラスタライズの順に処理する。
 *
 * **投影方式はオルソ（平行投影）。** 遠近感を付けるパースはあえて使わない。
 * SABERA の画面は小さく、遠近で縮む部分ができると経路間の比較にノイズが乗る。
 * オルソにしておくと三角形の内部・辺の深度が画面座標に対して厳密に線形になるので、
 * 重心座標やエッジの補間がそのまま正しい深度を返す（透視補正が要らない）。
 *
 * **カメラは固定の斜め視点。** 矢印の正面軸（+Z）は姿勢0度のときにそのまま画面奥へ
 * 一直線に伸びるので、カメラも同じ軸から見ると軸の断面しか見えず「3D」に見えない。
 * そこで姿勢を適用した後に [CAMERA_TILT]（X軸まわり固定角）をもう一段掛けて、
 * 常に斜め上から見下ろす向きにしてある。実物のカメラがあるわけではなく、
 * 見やすさのためだけに選んだ値。
 *
 * **縦横比は補正する。** [jp.jig.sabera.hello.flipbook.FlipbookScene] は
 * 「どちらの経路でも同じ動きに見える」ことだけを保証すればよく、縦横比を
 * あえて補正していない（`FlipbookScene.kt:16-19`）。しかしここでは経路ごとの
 * 見え方そのものを比べたいので、幅と高さを別々の倍率で引き伸ばすと矢印の形が
 * 経路ごとに変わってしまい、歪みと経路差の区別がつかなくなる。[project] は
 * `min(width, height)` 基準の単一の倍率だけを使う。
 */
object ArrowRaster {

    private const val DEG_TO_RAD = (PI / 180.0).toFloat()

    /** 固定カメラ角。大きいほど真上から見下ろす見た目になる */
    private const val CAMERA_TILT_DEGREES = 55f
    private val CAMERA_TILT = Mat3.rotationX(CAMERA_TILT_DEGREES * DEG_TO_RAD)

    /** 投影後、外枠にぴったり合わせず少し余白を残す倍率 */
    private const val FIT_MARGIN = 0.82f

    /** 陰影用の固定光源方向（カメラ空間）。正面よりやや右上から当てる */
    private val LIGHT_DIR = Vec3(0.35f, 0.55f, 1f).normalized()

    /** 光が当たらない面も真っ黒にしない下駄。0 は「何も無い背景」専用に空けておく */
    private const val AMBIENT = 0.15f

    /** [renderMask] で 1 セルを何 x 何点で調べるか */
    private const val MASK_SUPERSAMPLE = 3

    /** 隠線判定の許容誤差。同一面上の辺が自身の z 値とわずかにずれても隠れ扱いにしない */
    private const val EDGE_DEPTH_EPS = 0.01f

    /** 稜線の明るさ。0..7 の最大値で描く */
    private const val EDGE_LEVEL = 7

    /**
     * 画面座標系に投影した三角形。深度 [az]/[bz]/[cz] はカメラ空間の Z で、
     * 大きいほど手前（カメラに近い）。
     */
    private class Projected(
        val ax: Float, val ay: Float, val az: Float,
        val bx: Float, val by: Float, val bz: Float,
        val cx: Float, val cy: Float, val cz: Float,
        val level: Int,
    )

    private class RasterResult(val levels: IntArray, val depth: FloatArray)

    /** 面の法線から 0..7 の階調を出す。[GrayscaleImage] は実機と同じ 1画素1バイト */
    fun renderGray(pose: ArrowPose, style: ArrowStyle, width: Int, height: Int): GrayscaleImage {
        require(width > 0 && height > 0) { "width, height は1以上" }
        val projected = project(pose, width, height)
        val levels = when (style) {
            ArrowStyle.FILLED -> rasterizeFilled(projected, width, height).levels
            ArrowStyle.WIREFRAME -> rasterizeWireframe(projected, width, height)
        }
        val pixels = ByteArray(width * height) { i -> (levels[i].coerceIn(0, 7) * 32).toByte() }
        return GrayscaleImage(width, height, pixels)
    }

    /**
     * 文字グリッド用の点灯マスクを作る。true が点灯するセル。添字は `row * cols + col`。
     *
     * **3x3 のスーパーサンプルは [jp.jig.sabera.hello.flipbook.FlipbookScene.cellCovered]
     * （`private`）と同じ考え方の重複である。** 本家はあちら（既存のパラパラ漫画で
     * 実績がある）で、ここは別パッケージから private な実装に手が届かないための
     * 写しにすぎない。ロジックを変えるときはどちらも見比べること。
     *
     * 実装はセル解像度の3倍の大きさで [renderGray] と同じラスタライズ処理を1回走らせ、
     * 3x3 の各点が1つでも面（[ArrowStyle.WIREFRAME] なら辺）に触れていれば
     * そのセルを点灯とする（閾値1/9での2値化）。
     */
    fun renderMask(pose: ArrowPose, style: ArrowStyle, cols: Int, rows: Int): BooleanArray {
        require(cols > 0 && rows > 0) { "cols, rows は1以上" }
        val sw = cols * MASK_SUPERSAMPLE
        val sh = rows * MASK_SUPERSAMPLE
        val projected = project(pose, sw, sh)
        val levels = when (style) {
            ArrowStyle.FILLED -> rasterizeFilled(projected, sw, sh).levels
            ArrowStyle.WIREFRAME -> rasterizeWireframe(projected, sw, sh)
        }
        val mask = BooleanArray(cols * rows)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var covered = false
                outer@ for (sy in 0 until MASK_SUPERSAMPLE) {
                    for (sx in 0 until MASK_SUPERSAMPLE) {
                        val px = col * MASK_SUPERSAMPLE + sx
                        val py = row * MASK_SUPERSAMPLE + sy
                        if (levels[py * sw + px] > 0) {
                            covered = true
                            break@outer
                        }
                    }
                }
                mask[row * cols + col] = covered
            }
        }
        return mask
    }

    /** モデル座標 → 姿勢 → 固定カメラ角 → 画面座標。法線も同じ回転で運ぶ */
    private fun project(pose: ArrowPose, width: Int, height: Int): List<Projected> {
        val scale = minOf(width, height) * FIT_MARGIN / (2f * ArrowMesh.boundingRadius)
        val cx0 = width / 2f
        val cy0 = height / 2f
        return ArrowMesh.triangles.map { tri ->
            val wa = CAMERA_TILT.times(pose.rotation.times(tri.a))
            val wb = CAMERA_TILT.times(pose.rotation.times(tri.b))
            val wc = CAMERA_TILT.times(pose.rotation.times(tri.c))
            val wn = CAMERA_TILT.times(pose.rotation.times(tri.normal))
            val intensity = wn.dot(LIGHT_DIR).coerceIn(0f, 1f)
            val level = ((AMBIENT + intensity * (1f - AMBIENT)) * 7f).roundToInt().coerceIn(0, 7)
            Projected(
                cx0 + wa.x * scale, cy0 - wa.y * scale, wa.z,
                cx0 + wb.x * scale, cy0 - wb.y * scale, wb.z,
                cx0 + wc.x * scale, cy0 - wc.y * scale, wc.z,
                level,
            )
        }
    }

    /** 塗りつぶしラスタライズ。重心座標で深度を補間し、zバッファで手前の面だけ残す */
    private fun rasterizeFilled(triangles: List<Projected>, width: Int, height: Int): RasterResult {
        val levels = IntArray(width * height)
        val depth = FloatArray(width * height) { Float.NEGATIVE_INFINITY }
        for (t in triangles) {
            val minX = floor(minOf(t.ax, t.bx, t.cx)).toInt().coerceIn(0, width - 1)
            val maxX = ceil(maxOf(t.ax, t.bx, t.cx)).toInt().coerceIn(0, width - 1)
            val minY = floor(minOf(t.ay, t.by, t.cy)).toInt().coerceIn(0, height - 1)
            val maxY = ceil(maxOf(t.ay, t.by, t.cy)).toInt().coerceIn(0, height - 1)
            if (minX > maxX || minY > maxY) continue
            val area = edge(t.ax, t.ay, t.bx, t.by, t.cx, t.cy)
            if (abs(area) < 1e-6f) continue // 画面上でつぶれた三角形。塗る面積が無い
            for (py in minY..maxY) {
                val sy = py + 0.5f
                for (px in minX..maxX) {
                    val sx = px + 0.5f
                    val w0 = edge(t.bx, t.by, t.cx, t.cy, sx, sy)
                    val w1 = edge(t.cx, t.cy, t.ax, t.ay, sx, sy)
                    val w2 = edge(t.ax, t.ay, t.bx, t.by, sx, sy)
                    // 三角形の周る向きに関わらず判定できるよう、符号をそろえて見る
                    val inside = (w0 >= 0f && w1 >= 0f && w2 >= 0f) || (w0 <= 0f && w1 <= 0f && w2 <= 0f)
                    if (!inside) continue
                    val idx = py * width + px
                    val b0 = w0 / area
                    val b1 = w1 / area
                    val b2 = w2 / area
                    val z = b0 * t.az + b1 * t.bz + b2 * t.cz
                    if (z > depth[idx]) {
                        depth[idx] = z
                        levels[idx] = t.level
                    }
                }
            }
        }
        return RasterResult(levels, depth)
    }

    /**
     * 稜線（ワイヤーフレーム）ラスタライズ。
     *
     * 先に [rasterizeFilled] と同じ手順で深度バッファだけを起こし（塗りの階調は捨てる）、
     * 各三角形の3辺を画面上でなぞりながら、その点の深度がバッファの値と
     * ほぼ一致する（＝そこでは自分が一番手前の面である）ときだけ線を引く。
     * 一致しなければ、その辺は別の面に隠れている。
     */
    private fun rasterizeWireframe(triangles: List<Projected>, width: Int, height: Int): IntArray {
        val depth = rasterizeFilled(triangles, width, height).depth
        val levels = IntArray(width * height)
        for (t in triangles) {
            drawEdge(t.ax, t.ay, t.az, t.bx, t.by, t.bz, width, height, depth, levels)
            drawEdge(t.bx, t.by, t.bz, t.cx, t.cy, t.cz, width, height, depth, levels)
            drawEdge(t.cx, t.cy, t.cz, t.ax, t.ay, t.az, width, height, depth, levels)
        }
        return levels
    }

    private fun drawEdge(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        width: Int,
        height: Int,
        depth: FloatArray,
        levels: IntArray,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val steps = max(1, max(abs(dx), abs(dy)).roundToInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val px = (x0 + dx * t).roundToInt()
            val py = (y0 + dy * t).roundToInt()
            if (px !in 0 until width || py !in 0 until height) continue
            val z = z0 + (z1 - z0) * t
            val idx = py * width + px
            if (z >= depth[idx] - EDGE_DEPTH_EPS) {
                levels[idx] = EDGE_LEVEL
            }
        }
    }

    private fun edge(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
        (px - ax) * (by - ay) - (py - ay) * (bx - ax)
}
