package com.tmvision.mahjong.vision

import android.graphics.Bitmap
import com.tmvision.engine.Tiles
import java.io.File
import kotlin.math.sqrt

/**
 * 牌面辨識的核心：把每張牌縮成小灰階圖當特徵，跟「你自己拍的參考牌」比對。
 *
 * ## 為什麼不用 YOLO / TFLite
 * 通用物件偵測要解的是「畫面裡哪裡有牌」，而手牌是**一列等寬、緊鄰、立著**的牌——
 * 只要你把手牌對進畫面上的框，把框等分成 16 或 17 格，切點必然是對的。
 * 偵測那一半直接消失，剩下的「這格是什麼牌」用比對就夠了，而且：
 *
 * - 不用下載任何模型，APK 不會變肥
 * - 比對的是**你自己那副牌**，比任何公開模型都準
 * - 認錯的時候，把那格存進參考庫就學會了，不用重新訓練
 */

/** 特徵圖大小。12x16 已經足夠分辨 34 種牌面，又小到可以瞬間比對幾百個樣本 */
const val FEATURE_WIDTH = 12
const val FEATURE_HEIGHT = 16
const val FEATURE_SIZE = FEATURE_WIDTH * FEATURE_HEIGHT

/**
 * 把一張牌的圖轉成特徵向量。
 *
 * 先縮成 12x16 灰階，再**去平均、除標準差**——這一步很重要：
 * 它讓特徵對「整體變亮／變暗／對比不同」免疫，所以換一盞燈也不用重拍參考牌。
 */
fun featureOf(source: Bitmap): FloatArray {
    val small = Bitmap.createScaledBitmap(source, FEATURE_WIDTH, FEATURE_HEIGHT, true)
    val pixels = IntArray(FEATURE_SIZE)
    small.getPixels(pixels, 0, FEATURE_WIDTH, 0, 0, FEATURE_WIDTH, FEATURE_HEIGHT)
    if (small !== source) small.recycle()

    val feature = FloatArray(FEATURE_SIZE)
    for (i in feature.indices) {
        val pixel = pixels[i]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        feature[i] = 0.299f * r + 0.587f * g + 0.114f * b
    }

    var mean = 0f
    for (value in feature) mean += value
    mean /= feature.size

    var variance = 0f
    for (value in feature) variance += (value - mean) * (value - mean)
    val deviation = sqrt(variance / feature.size).coerceAtLeast(1f)

    for (i in feature.indices) feature[i] = (feature[i] - mean) / deviation
    return feature
}

/** 兩個特徵向量的距離，越小越像 */
fun featureDistance(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) {
        val diff = a[i] - b[i]
        sum += diff * diff
    }
    return sqrt(sum)
}

/**
 * 一格的辨識結果。
 *
 * @property distance 與最像的那張參考牌的距離
 * @property margin   與「第二像的**其他**牌」差多少。這個值才是信心的關鍵——
 *                    距離小但兩張牌都很像（margin 小）代表它其實在猜。
 */
data class TileMatch(val tile: Int, val distance: Float, val margin: Float) {
    val name: String get() = Tiles.displayName(tile)

    /** 夠有把握才自動填入，否則標成問號讓人確認 */
    val isConfident: Boolean get() = distance < MAX_DISTANCE && margin > MIN_MARGIN

    companion object {
        /** 超過這個距離代表根本不像任何一張參考牌（可能框沒對準） */
        const val MAX_DISTANCE = 9.0f

        /** 跟第二名差距太小就是在猜 */
        const val MIN_MARGIN = 1.2f
    }
}

/**
 * 參考牌庫：記住每一種牌長什麼樣。
 *
 * 一開始由「教學模式」建立（排一列拍一張，四次拍完 34 種），
 * 之後每次你修正一個認錯的格子，那格就會被存進來，下次就認得了——
 * 這是 few-shot learning，不需要梯度下降也不需要重新訓練。
 */
class TileMemory(private val storage: File) {

    private val tiles = ArrayList<Int>()
    private val features = ArrayList<FloatArray>()

    /** 總共記了幾個樣本 */
    val sampleCount: Int get() = tiles.size

    /** 認得幾種牌 */
    val knownKinds: Int get() = tiles.distinct().size

    /** 34 種都認得了才算完成教學 */
    val isReady: Boolean get() = knownKinds >= Tiles.KINDS

    fun samplesFor(tile: Int): Int = tiles.count { it == tile }

    /** 記住「這張圖是這種牌」 */
    fun remember(tile: Int, feature: FloatArray) {
        require(tile in 0 until Tiles.KINDS) { "牌索引不合法: $tile" }
        tiles.add(tile)
        features.add(feature)
    }

    /**
     * 找出最像的牌。
     *
     * 先算出「每一種牌」各自最近的距離，再從中挑第一名與第二名，
     * 這樣 margin 才是跟**不同牌**的差距（同一種牌有多個樣本不該互相稀釋信心）。
     */
    fun match(feature: FloatArray): TileMatch? {
        if (tiles.isEmpty()) return null

        val bestPerTile = FloatArray(Tiles.KINDS) { Float.MAX_VALUE }
        for (index in tiles.indices) {
            val distance = featureDistance(feature, features[index])
            val tile = tiles[index]
            if (distance < bestPerTile[tile]) bestPerTile[tile] = distance
        }

        var bestTile = -1
        var best = Float.MAX_VALUE
        var runnerUp = Float.MAX_VALUE
        for (tile in bestPerTile.indices) {
            val distance = bestPerTile[tile]
            if (distance < best) {
                runnerUp = best
                best = distance
                bestTile = tile
            } else if (distance < runnerUp) {
                runnerUp = distance
            }
        }
        if (bestTile < 0) return null

        val margin = if (runnerUp == Float.MAX_VALUE) Float.MAX_VALUE else runnerUp - best
        return TileMatch(bestTile, best, margin)
    }

    fun clear() {
        tiles.clear()
        features.clear()
        if (storage.exists()) storage.delete()
    }

    /** 存檔。格式是每行一個樣本：`牌索引,f1,f2,...`，純文字方便出問題時直接看 */
    fun save() {
        storage.parentFile?.mkdirs()
        storage.bufferedWriter().use { writer ->
            for (index in tiles.indices) {
                writer.write(tiles[index].toString())
                for (value in features[index]) {
                    writer.write(",")
                    writer.write(value.toString())
                }
                writer.newLine()
            }
        }
    }

    fun load() {
        tiles.clear()
        features.clear()
        if (!storage.exists()) return
        storage.forEachLine { line ->
            val parts = line.split(",")
            if (parts.size == FEATURE_SIZE + 1) {
                val tile = parts[0].toIntOrNull()
                if (tile != null && tile in 0 until Tiles.KINDS) {
                    val feature = FloatArray(FEATURE_SIZE) { parts[it + 1].toFloatOrNull() ?: 0f }
                    tiles.add(tile)
                    features.add(feature)
                }
            }
        }
    }
}
