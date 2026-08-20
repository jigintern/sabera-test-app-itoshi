package jp.jig.sabera.hello.arrow3d

/**
 * 三角形1枚。頂点はモデル座標。[normal] は面法線で、平面を1枚として扱うぶん
 * 頂点ごとの補間はしない（フラットシェーディング。面ごとに1階調で塗る）。
 */
data class Triangle(val a: Vec3, val b: Vec3, val c: Vec3) {
    val normal: Vec3 by lazy { (b - a).cross(c - a).normalized() }

    val center: Vec3 get() = (a + b + c) * (1f / 3f)
}

/**
 * 矢印を三角形の列で持つメッシュ。軸（角柱）＋頭（角錐）。モデル座標で +Z を前方に取る。
 *
 * [ArrowPose] の回転規約（[Mat3.rotationY] の KDoc）と対にして読むこと。方位0度・
 * ピッチ0度・ロール0度の姿勢で矢印の先端が +Z を向くように、ここではその軸に
 * 沿ってメッシュを組む。上下方向（画面の縦）は +Y、左右は +X に取る。
 *
 * **頭の付け根の幅を軸と同じにしてある。** 昔ながらの矢印は頭が軸より広く張り出す
 * (flare) が、そうすると「軸→頭」の継ぎ目に軸に垂直な平らな肩の面ができ、
 * この面の外向き法線が全体の重心方向からの単純な判定では正しく決まらない
 * （肩の面だけ重心より前にあるのに法線は後ろ向き、という食い違いが起きる）。
 * 軸と頭の付け根を同じ幅にすれば、角柱の上に角錐を継ぎ目なく載せた形になり、
 * これは断面が「家の形」（四角の上に三角の屋根）の五角形と同じ意味で凸多面体になる。
 * 凸多面体なら「重心から見て外向きかどうか」で法線の向きを一意に直せるので、
 * ここでは頂点の並び順を1つ1つ検算せず、[fixOutwardNormals] で機械的に揃えている。
 */
object ArrowMesh {

    /** 軸・頭の付け根の半幅（X, Y 方向） */
    private const val HALF = 0.16f

    /** 軸の後端 */
    private const val SHAFT_BACK_Z = -0.55f

    /** 軸と頭の境目（頭の付け根） */
    private const val HEAD_BASE_Z = 0.10f

    /** 頭の先端 */
    private const val HEAD_TIP_Z = 0.60f

    /**
     * メッシュ全体を包む球の半径。[jp.jig.sabera.hello.arrow3d.ArrowRaster] が
     * 投影の拡大率を決めるのに使う。頂点のうち原点から最も遠いのは頭の先端
     * (0,0,HEAD_TIP_Z) か軸後端の角 (±HALF,±HALF,SHAFT_BACK_Z) のどちらか。
     */
    val boundingRadius: Float = run {
        val tip = Vec3(0f, 0f, HEAD_TIP_Z).length()
        val backCorner = Vec3(HALF, HALF, SHAFT_BACK_Z).length()
        maxOf(tip, backCorner)
    }

    val triangles: List<Triangle> by lazy { buildMesh() }

    private fun buildMesh(): List<Triangle> {
        // 軸の後端の四隅
        val b0 = Vec3(-HALF, -HALF, SHAFT_BACK_Z)
        val b1 = Vec3(HALF, -HALF, SHAFT_BACK_Z)
        val b2 = Vec3(HALF, HALF, SHAFT_BACK_Z)
        val b3 = Vec3(-HALF, HALF, SHAFT_BACK_Z)

        // 軸の前端＝頭の付け根の四隅（軸と共有するので同じ半幅）
        val m0 = Vec3(-HALF, -HALF, HEAD_BASE_Z)
        val m1 = Vec3(HALF, -HALF, HEAD_BASE_Z)
        val m2 = Vec3(HALF, HALF, HEAD_BASE_Z)
        val m3 = Vec3(-HALF, HALF, HEAD_BASE_Z)

        val tip = Vec3(0f, 0f, HEAD_TIP_Z)

        val raw = buildList {
            // 後端のキャップ
            addAll(quad(b0, b1, b2, b3))
            // 軸の側面4枚
            addAll(quad(b0, b1, m1, m0))
            addAll(quad(b1, b2, m2, m1))
            addAll(quad(b2, b3, m3, m2))
            addAll(quad(b3, b0, m0, m3))
            // 頭（角錐）の側面4枚。付け根の面は軸の前端キャップと重なる内部面なので描かない
            add(Triangle(m0, m1, tip))
            add(Triangle(m1, m2, tip))
            add(Triangle(m2, m3, tip))
            add(Triangle(m3, m0, tip))
        }
        return fixOutwardNormals(raw)
    }

    /** 四角形 a,b,c,d（この順で周る）を三角形2枚に割る。周る向きはまだ揃っていなくてよい */
    private fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3): List<Triangle> =
        listOf(Triangle(a, b, c), Triangle(a, c, d))

    /**
     * 全三角形の法線を「メッシュの重心から見て外向き」に揃える。
     *
     * 凸多面体であることが前提（クラス KDoc 参照）。法線と
     * (三角形の重心 - メッシュ重心) の内積が負なら、頂点 b, c を入れ替えて
     * 法線を反転させる。これで頂点の列挙順を1枚ずつ検算しなくても済む。
     */
    private fun fixOutwardNormals(raw: List<Triangle>): List<Triangle> {
        val vertices = raw.flatMap { listOf(it.a, it.b, it.c) }
        val centroid = vertices.reduce { acc, v -> acc + v } * (1f / vertices.size)
        return raw.map { t ->
            val outward = (t.center - centroid).dot(t.normal) >= 0f
            if (outward) t else Triangle(t.a, t.c, t.b)
        }
    }
}
