package jp.jig.sabera.hello.flipbook

import jp.jig.sabera.hello.image.CanvasImageBudget
import jp.jig.sabera.hello.image.ThreeBitRle
import kotlinx.coroutines.yield

/** 1コマぶんの見積り */
data class FrameCost(
    val frame: Int,
    /** 3bit RLE で圧縮した後のバイト数 */
    val encoded: Int,
    val packets: Int,
    val estimatedMs: Long,
)

/**
 * ある大きさで1周ぶんを送るときの見積り。
 *
 * **コマごとに違う**のが肝心なところで、圧縮後サイズは絵の中身で変わる。
 * 予算に収まるかは「一番重いコマ」で判断しないと、再生の途中で SDK の require に
 * 当たって落ちる。
 */
data class CanvasImageCost(
    val width: Int,
    val height: Int,
    val frames: List<FrameCost>,
) {
    val worst: FrameCost? = frames.maxByOrNull { it.encoded }
    val lightest: FrameCost? = frames.minByOrNull { it.encoded }

    val averageMs: Long =
        if (frames.isEmpty()) 0L else frames.sumOf { it.estimatedMs } / frames.size

    val averagePackets: Int =
        if (frames.isEmpty()) 0 else frames.sumOf { it.packets } / frames.size

    /** 一番重いコマで律速したときに出せる fps。これを超える指定はキューが伸びるだけ */
    val ceilingFps: Float =
        worst?.estimatedMs?.takeIf { it > 0 }?.let { 1000f / it } ?: 0f

    fun costOf(frame: Int): FrameCost? = frames.getOrNull(frame)
}

/**
 * 1周ぶんの各コマを実際にラスタライズして圧縮後サイズを数える。
 *
 * 重いので必ず [kotlinx.coroutines.Dispatchers.Default] などで呼ぶこと。544x340 を
 * 48コマぶん数えると 900万画素を舐めることになる。コマごとに [yield] するので、
 * スライダーを動かして条件が変わったときは途中で畳める。
 */
suspend fun measureFrames(
    scene: FlipbookScene,
    frameCount: Int,
    width: Int,
    height: Int,
): CanvasImageCost {
    val frames = ArrayList<FrameCost>(frameCount)
    for (i in 0 until frameCount) {
        yield()
        val encoded = ThreeBitRle.encodedSize(renderImage(scene, i, frameCount, width, height).pixels)
        val packets = CanvasImageBudget.packetCount(encoded)
        frames += FrameCost(
            frame = i,
            encoded = encoded,
            packets = packets,
            estimatedMs = CanvasImageBudget.estimatedMs(packets),
        )
    }
    return CanvasImageCost(width, height, frames)
}

/**
 * 送る大きさの候補。キャンバスの 16:10 に合わせてある。
 *
 * 末尾の2つがこのテストの核心で、544x340 はバッファのほぼ限界（余りが 10,080B しかなく、
 * RLE の下限 5,780B を差し引くと 4,300B しか自由が無い）、576x360 の全画面は
 * 中身に関係なく必ず落ちる。
 */
val CANVAS_IMAGE_PRESETS = listOf(
    96 to 60,
    144 to 90,
    192 to 120,
    256 to 160,
    320 to 200,
    384 to 240,
    448 to 280,
    512 to 320,
    544 to 340,
    576 to 360,
)
