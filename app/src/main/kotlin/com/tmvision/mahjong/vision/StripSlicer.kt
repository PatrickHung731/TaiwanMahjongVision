package com.tmvision.mahjong.vision

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * 對準框的位置與大小。
 *
 * 框的高度是**依牌數算出來的**，不是固定值：16 張牌並排是一條又寬又扁的長條，
 * 框的比例必須跟真實牌型一致，你才對得準、切出來的每一格也才會剛好是一張牌。
 *
 * 預覽畫面上畫的框與實際裁切用的是同一組計算，所以「看到的框」就是「切的範圍」。
 */
object Guide {
    /** 左右邊界（畫面寬度的比例） */
    const val LEFT = 0.03f
    const val RIGHT = 0.97f

    /** 框的垂直中心 */
    const val CENTER_Y = 0.5f

    /** 一張麻將牌立起來的高寬比，大約 1.5 */
    private const val TILE_ASPECT = 1.5f

    /** 框比實際牌高一點點，留一些對準的餘裕 */
    private const val SLACK = 1.12f

    /**
     * 框的高度（畫面高度的比例）。
     *
     * @param count 要切成幾格
     * @param frameAspect 畫面的寬 / 高（直式 4:3 相機約 0.75）
     */
    fun heightFraction(count: Int, frameAspect: Float): Float {
        if (count <= 0) return 0.1f
        val sliceWidthFraction = (RIGHT - LEFT) / count
        return (TILE_ASPECT * sliceWidthFraction * frameAspect * SLACK).coerceIn(0.03f, 0.6f)
    }

    fun topFraction(count: Int, frameAspect: Float): Float =
        CENTER_Y - heightFraction(count, frameAspect) / 2f

    fun bottomFraction(count: Int, frameAspect: Float): Float =
        CENTER_Y + heightFraction(count, frameAspect) / 2f
}

/**
 * 把畫面中對準框內的長條，等分切成 [count] 張牌。
 *
 * 手牌是緊鄰排列且等寬的，所以等分就是正確的切法——
 * 這正是不需要物件偵測模型的原因。
 */
fun sliceStrip(frame: Bitmap, count: Int): List<Bitmap> {
    if (count <= 0) return emptyList()
    val frameAspect = frame.width.toFloat() / frame.height.toFloat()

    val top = (Guide.topFraction(count, frameAspect) * frame.height).toInt()
        .coerceIn(0, frame.height - 1)
    val bottom = (Guide.bottomFraction(count, frameAspect) * frame.height).toInt()
        .coerceIn(top + 1, frame.height)
    val left = (Guide.LEFT * frame.width).toInt().coerceIn(0, frame.width - 1)
    val right = (Guide.RIGHT * frame.width).toInt().coerceIn(left + 1, frame.width)

    val stripWidth = right - left
    val height = bottom - top

    return (0 until count).map { index ->
        val x0 = left + stripWidth * index / count
        val x1 = left + stripWidth * (index + 1) / count
        Bitmap.createBitmap(frame, x0, top, (x1 - x0).coerceAtLeast(1), height)
    }
}

/** 相機給的影像是橫的，要先轉正才能切 */
fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
