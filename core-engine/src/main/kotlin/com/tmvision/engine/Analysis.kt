package com.tmvision.engine

/**
 * 一種牌 + 張數。用於「有效進張」清單：`(3筒, 剩 4 張)`。
 */
data class TileCount(val tile: Int, val count: Int) {
    val name: String get() = Tiles.displayName(tile)
    override fun toString(): String = "$name($count)"
}

/**
 * 「切掉這張牌」的評估結果。
 *
 * @property discard        建議打出的牌
 * @property shantenAfter   打出後的向聽數（0 = 聽牌）
 * @property acceptance     打出後的有效進張（能讓向聽數再減 1 的牌）；
 *                          若 [shantenAfter] 為 0，這就是「聽的牌」
 * @property acceptanceTiles 有效進張的**總張數**（已扣掉自己手上與牌河已見的）
 */
data class DiscardOption(
    val discard: Int,
    val shantenAfter: Int,
    val acceptance: List<TileCount>,
    val acceptanceTiles: Int,
) {
    /** 有效進張的種類數 */
    val acceptanceKinds: Int get() = acceptance.size

    /** 打出這張後是否聽牌 */
    val isTenpai: Boolean get() = shantenAfter == 0

    val discardName: String get() = Tiles.displayName(discard)

    override fun toString(): String =
        "丟$discardName →${shantenAfter}向聽, 進張 $acceptanceTiles 張 ${acceptance.joinToString(", ")}"
}

/**
 * 一次完整的手牌分析結果，也是 UI 覆蓋層的資料來源。
 *
 * @property shanten        目前向聽數：`-1` 已胡、`0` 聽牌、`n` 還差 n 步
 * @property acceptance     **待摸狀態**（16 張）的有效進張；聽牌時即為「聽的牌」
 * @property discardOptions **待切狀態**（17 張）的切牌建議，已依好壞排序，最好的在第一個
 * @property computeNanos   本次計算耗時（奈秒），用來監控 1 FPS 推論預算
 */
data class HandAnalysis(
    val shanten: Int,
    val handSize: Int,
    val meldedSets: Int,
    val isWinning: Boolean,
    val acceptance: List<TileCount>,
    val discardOptions: List<DiscardOption>,
    val computeNanos: Long,
) {
    /** 最佳切牌建議（待摸狀態時為 null） */
    val bestDiscard: DiscardOption? get() = discardOptions.firstOrNull()

    /** 是否聽牌 */
    val isTenpai: Boolean get() = shanten == 0

    /** 有效進張總張數 */
    val acceptanceTiles: Int get() = acceptance.sumOf { it.count }

    val computeMillis: Double get() = computeNanos / 1_000_000.0

    /**
     * AR 覆蓋層用的單行提示，例如：
     * ```
     * 建議丟牌：9條 | 聽牌 | 進張機會：3筒, 6筒 (剩餘 5 張)
     * 建議丟牌：1筒 | 1進聽 | 進張機會：2筒, 3筒, 6筒 (剩餘 35 張)
     * 聽牌 | 進張機會：3筒, 6筒 (剩餘 7 張)
     * ```
     */
    fun overlayText(maxTiles: Int = 6): String {
        if (isWinning) return "🎉 已胡牌！"
        val best = bestDiscard
        if (best != null) {
            val head = "建議丟牌：${best.discardName}"
            val stage = if (best.isTenpai) "聽牌" else "${best.shantenAfter}進聽"
            if (best.acceptance.isEmpty()) return "$head | $stage | 無進張機會"
            return "$head | $stage | 進張機會：${tileList(best.acceptance, maxTiles)} (剩餘 ${best.acceptanceTiles} 張)"
        }
        val stage = if (isTenpai) "聽牌" else "${shanten}進聽"
        if (acceptance.isEmpty()) return "$stage | 無進張機會"
        return "$stage | 進張機會：${tileList(acceptance, maxTiles)} (剩餘 $acceptanceTiles 張)"
    }

    /** 開發/除錯用的多行摘要 */
    fun detailText(topN: Int = 5): String = buildString {
        appendLine("向聽數：$shanten${if (isWinning) "（已胡）" else if (isTenpai) "（聽牌）" else ""}")
        appendLine("手牌：$handSize 張，副露 $meldedSets 組，計算耗時 ${"%.2f".format(computeMillis)} ms")
        if (acceptance.isNotEmpty()) {
            appendLine("有效進張：${acceptance.joinToString(", ")}  合計 $acceptanceTiles 張")
        }
        discardOptions.take(topN).forEachIndexed { index, option ->
            appendLine("${index + 1}. $option")
        }
    }

    private fun tileList(tiles: List<TileCount>, maxTiles: Int): String {
        val shown = tiles.take(maxTiles).joinToString(", ") { it.name }
        return if (tiles.size > maxTiles) "$shown…等${tiles.size}種" else shown
    }
}
