package com.tmvision.engine

import kotlin.math.max
import kotlin.math.min

/**
 * 台灣麻將的「過水」規則——決定對手打過的牌到底有多安全。
 *
 * 這是**必須跟你的牌桌對齊**的設定：日本麻將的振聽是整局有效，
 * 台灣多數牌桌的過水只到自己下次摸牌為止，所以「現物」在台灣**遠遠沒有日麻安全**。
 */
enum class SafeTileRule(val genbutsuFactor: Double, val description: String) {
    /** 台灣最常見：過水只限制到自己下次摸牌，之後照樣能胡 → 現物只是弱訊號 */
    TAIWAN_PASS_UNTIL_NEXT_DRAW(0.35, "過水至下次摸牌（台灣常見）"),

    /** 整局都不能胡自己打過的牌（類似日麻振聽）→ 現物幾乎絕對安全 */
    PERMANENT_FURITEN(0.03, "整局振聽（類日麻）"),

    /** 沒有過水限制 → 打過的牌照樣被胡，現物完全沒有參考價值 */
    NONE(1.0, "無過水限制"),
}

/**
 * 危險度模型的可調參數。
 *
 * 這裡面的數字分兩種，註解都有標明：
 * - **統計值**：來自 `tools/generate_wait_stats.py` 的台灣 16 張模擬（見 [DangerStats]）
 * - **啟發式**：沒有公開數據可用，是合理推估，之後有真實牌譜可以校正
 */
data class DangerConfig(
    /** 牌桌的過水規則 */
    val safeTileRule: SafeTileRule = SafeTileRule.TAIWAN_PASS_UNTIL_NEXT_DRAW,

    /** 啟發式：每副露 1 組，聽牌機率的相對提升（吃碰的人進度比較快） */
    val meldSpeedUp: Double = 0.35,

    /** 啟發式：對手明顯在做同一花色（副露 ≥ 2 組同色）時，該花色的危險度倍率 */
    val flushSuitMultiplier: Double = 1.6,

    /** 啟發式：同上情境下，其他花色的危險度倍率 */
    val flushOtherSuitMultiplier: Double = 0.7,

    /** 聽牌機率的上限（再怎麼樣也不該當成 100%） */
    val maxTenpaiProbability: Double = 0.85,
) {
    companion object {
        val DEFAULT = DangerConfig()

        /** 嚴格振聽規則的牌桌 */
        val FURITEN_TABLE = DangerConfig(safeTileRule = SafeTileRule.PERMANENT_FURITEN)
    }
}

/**
 * 單張牌的危險度拆解，給 UI 說明「為什麼這張危險／安全」。
 */
data class DangerBreakdown(
    val tile: Int,
    val risk: Double,
    val perOpponent: Map<Seat, Double>,
    val reasons: List<String>,
) {
    val tileName: String get() = Tiles.displayName(tile)
    val percentText: String get() = "%.1f%%".format(risk * 100)
}

/**
 * 放槍機率估算。
 *
 * ## 這是估計值，不是真值
 * 對手的暗牌看不到，任何麻將軟體給的放槍機率都是**推估**。
 * 這裡的推估建立在三件鏡頭看得到的事實上：
 * 1. 他打過哪些牌（現物、筋、打牌節奏）
 * 2. 他吃碰了什麼（進度快慢、是不是在做同一花色）
 * 3. 場上已經出現幾張（他不可能握有已經現身的牌）
 *
 * ## 公式
 * ```
 * P(放槍 | 打這張) = 1 - Π 對手 (1 - P(他聽牌) × P(他聽這張 | 已聽牌))
 * ```
 *
 * - `P(他聽牌)` 來自台灣 16 張的模擬統計（[DangerStats.tenpaiRateByTurn]），再依副露數調整。
 * - `P(他聽這張 | 已聽牌)` 以各牌的被聽頻率為底，再套用現物、筋、壁、剩餘張數等修正。
 *
 * 正規化條件很明確：**在沒有任何額外資訊時，所有 34 種牌的機率總和 ≈ 平均聽牌張數**
 * （模擬結果約 1.7 種），所以量級不是憑空調出來的。
 */
class DangerEstimator(val config: DangerConfig = DangerConfig.DEFAULT) {

    /**
     * 對手已經聽牌的機率。
     *
     * 台灣麻將沒有立直，所以只能從巡目與副露數推估——這是整個模型裡最粗的一環，
     * 也是之後蒐集到真實牌譜時最該優先校正的參數。
     */
    fun tenpaiProbability(opponent: OpponentState, turn: Int): Double {
        val base = DangerStats.tenpaiRateByTurn(turn)
        val adjusted = base * (1.0 + config.meldSpeedUp * opponent.meldedSets)
        return min(adjusted, config.maxTenpaiProbability)
    }

    /**
     * 假設對手已經聽牌，他聽這張牌的機率。
     */
    fun hitProbability(
        tile: Int,
        opponent: OpponentState,
        table: TableState,
        seen: SeenTiles = table.seenTiles(),
    ): Double = weightOf(tile, opponent, table, seen) *
        DangerStats.AVERAGE_WAIT_KINDS / DangerStats.BASE_WEIGHT_TOTAL

    /** 打這張牌被**某一位**對手胡的機率 */
    fun dealInRisk(
        tile: Int,
        opponent: OpponentState,
        table: TableState,
        seen: SeenTiles = table.seenTiles(),
    ): Double = tenpaiProbability(opponent, table.turn) * hitProbability(tile, opponent, table, seen)

    /** 打這張牌被**任何一位**對手胡的機率 */
    fun dealInRisk(tile: Int, table: TableState, seen: SeenTiles = table.seenTiles()): Double {
        if (!table.hasOpponentInfo) return 0.0
        var safe = 1.0
        for (opponent in table.opponents) {
            safe *= (1.0 - dealInRisk(tile, opponent, table, seen))
        }
        return (1.0 - safe).coerceIn(0.0, 1.0)
    }

    /** 附上「為什麼」的完整估算，給 UI 顯示 */
    fun explain(tile: Int, table: TableState, seen: SeenTiles = table.seenTiles()): DangerBreakdown {
        val perOpponent = table.opponents.associate { it.seat to dealInRisk(tile, it, table, seen) }
        val reasons = ArrayList<String>()

        val genbutsuOf = table.opponents.filter { it.hasDiscarded(tile) }
        if (genbutsuOf.isNotEmpty()) {
            reasons.add("${genbutsuOf.joinToString("、") { it.seat.display }}打過（${config.safeTileRule.description}）")
        }
        if (Tiles.isSuited(tile)) {
            val suji = table.opponents.filter { hasSuji(tile, it) }
            if (suji.isNotEmpty()) reasons.add("${suji.joinToString("、") { it.seat.display }}的筋牌")
        }
        val unseen = table.unseenCount(tile, seen)
        when {
            // 字牌只能雙碰／單吊，絕張就是真安全；數牌還有嵌張與兩面，不能同一句話帶過
            unseen == 0 && Tiles.isHonor(tile) -> reasons.add("4 張全部現身，字牌絕張＝安全")
            unseen == 0 -> reasons.add("4 張全部現身，不會被雙碰／單吊（但仍可能被嵌張、兩面胡）")
            unseen == 1 -> reasons.add("只剩 1 張，湊不出雙碰")
        }
        if (hasWall(tile, table, seen)) reasons.add("壁牌擋住，兩面／嵌張機會下降")
        if (reasons.isEmpty()) reasons.add("無安全線索")

        return DangerBreakdown(tile, dealInRisk(tile, table, seen), perOpponent, reasons)
    }

    // ------------------------------------------------------------------
    // 權重計算：把「被聽頻率」依可見資訊逐項打折
    // ------------------------------------------------------------------

    /**
     * 把「被聽頻率」依三種聽型拆開，各自套用**正確**的修正。
     *
     * 這個拆分不是為了精緻，是為了不算錯：
     * - **兩面**：他握的是鄰牌 → 看筋牌與壁牌
     * - **嵌張／邊張**：他握的也是鄰牌 → 看鄰牌還在不在
     * - **雙碰／單吊**：他必須握有**這張牌本身** → 才看得出「還剩幾張」
     *
     * 常見的錯誤是把「這張牌 4 張全現身了」當成整張牌安全。
     * 對字牌來說沒錯（字牌只能雙碰／單吊），但對數牌來說是錯的——
     * 他可以握著 4 條 6 條等你打 5 條，跟場上還剩幾張 5 條一點關係都沒有。
     */
    private fun weightOf(tile: Int, opponent: OpponentState, table: TableState, seen: SeenTiles): Double {
        val base = DangerStats.baseWeight(tile)
        if (base <= 0.0) return 0.0

        val unseen = table.unseenCount(tile, seen)
        var weight =
            base * DangerStats.ryanmenShare(tile) * ryanmenFactor(tile, opponent, table, seen) +
                base * DangerStats.closedWaitShare(tile) * closedWaitFactor(tile, table, seen) +
                base * DangerStats.pairWaitShare(tile) * holdFactor(unseen)

        // 現物：他打過這張牌
        if (opponent.hasDiscarded(tile)) weight *= config.safeTileRule.genbutsuFactor

        // 染手：副露集中在同一花色
        weight *= flushMultiplier(tile, opponent)

        return max(0.0, weight)
    }

    /**
     * 兩面聽的修正。
     *
     * 一張牌最多有兩個方向會被兩面聽：
     * - 方向 A：對手握有 `(t+1, t+2)`，同時聽 `t` 與 `t+3`（需要 t 的點數 ≤ 6）
     * - 方向 B：對手握有 `(t-2, t-1)`，同時聽 `t-3` 與 `t`（需要 t 的點數 ≥ 4）
     *
     * 打掉筋牌（`t+3` 或 `t-3`）等於宣告該方向不存在——他若握著那個搭子，早就胡了。
     * 壁牌（做搭子需要的牌 4 張全在場上）同樣可以直接消掉一個方向。
     */
    private fun ryanmenFactor(tile: Int, opponent: OpponentState, table: TableState, seen: SeenTiles): Double {
        if (!Tiles.isSuited(tile)) return 0.0
        val rank = Tiles.rank(tile)

        val directionA = rank <= 6 &&
            !opponent.hasDiscarded(tile + 3) &&
            table.unseenCount(tile + 1, seen) > 0 &&
            table.unseenCount(tile + 2, seen) > 0
        val directionB = rank >= 4 &&
            !opponent.hasDiscarded(tile - 3) &&
            table.unseenCount(tile - 1, seen) > 0 &&
            table.unseenCount(tile - 2, seen) > 0

        val possibleDirections = (if (rank <= 6) 1 else 0) + (if (rank >= 4) 1 else 0)
        if (possibleDirections == 0) return 0.0
        val aliveDirections = (if (directionA) 1 else 0) + (if (directionB) 1 else 0)
        return aliveDirections.toDouble() / possibleDirections
    }

    /**
     * 嵌張／邊張的修正——他握的是鄰牌，所以只看鄰牌還在不在。
     *
     * - 嵌張：他握 `(t-1, t+1)`，需要 t 的點數在 2~8
     * - 邊張：只有兩種形狀——握 `(1, 2)` 聽 3、握 `(8, 9)` 聽 7
     */
    private fun closedWaitFactor(tile: Int, table: TableState, seen: SeenTiles): Double {
        if (!Tiles.isSuited(tile)) return 0.0
        val rank = Tiles.rank(tile)
        var possible = 0
        var alive = 0

        if (rank in 2..8) {                                  // 嵌張
            possible++
            if (available(tile - 1, tile, table, seen) && available(tile + 1, tile, table, seen)) alive++
        }
        if (rank == 3) {                                     // 邊張：12 聽 3
            possible++
            if (available(tile - 2, tile, table, seen) && available(tile - 1, tile, table, seen)) alive++
        }
        if (rank == 7) {                                     // 邊張：89 聽 7
            possible++
            if (available(tile + 1, tile, table, seen) && available(tile + 2, tile, table, seen)) alive++
        }
        return if (possible == 0) 0.0 else alive.toDouble() / possible
    }

    /**
     * 雙碰／單吊的修正——這種聽型他必須握有**這張牌本身**，
     * 所以場上還剩幾張沒現身直接決定可能性。
     */
    private fun holdFactor(unseen: Int): Double = when (unseen) {
        0 -> 0.0         // 4 張全部現身，他手上不可能有
        1 -> 0.35        // 只剩 1 張，湊不出雙碰，只可能單吊
        2 -> 0.70
        3 -> 0.95
        else -> 1.0
    }

    /** 這張鄰牌是否還可能在對手手上（同花色、且還沒 4 張全部現身） */
    private fun available(neighbour: Int, origin: Int, table: TableState, seen: SeenTiles): Boolean =
        Tiles.isSuited(neighbour) &&
            Tiles.suitStart(neighbour) == Tiles.suitStart(origin) &&
            table.unseenCount(neighbour, seen) > 0

    /** 對手副露集中在同一花色時的加權 */
    private fun flushMultiplier(tile: Int, opponent: OpponentState): Double {
        val suit = dominantSuit(opponent) ?: return 1.0
        return when {
            Tiles.isHonor(tile) -> 1.0                       // 字牌在染手裡照樣有用
            Tiles.suitStart(tile) == suit -> config.flushSuitMultiplier
            else -> config.flushOtherSuitMultiplier
        }
    }

    /** 副露是否集中在單一花色（≥ 2 組同色數牌） */
    private fun dominantSuit(opponent: OpponentState): Int? {
        if (opponent.meldedSets < 2) return null
        val perSuit = IntArray(3)
        var honors = 0
        for (tile in 0 until Tiles.KINDS) {
            val count = opponent.meldedTiles[tile]
            if (count == 0) continue
            if (Tiles.isHonor(tile)) honors += count else perSuit[tile / Tiles.SUIT_SIZE] += count
        }
        val total = perSuit.sum() + honors
        if (total == 0) return null
        val best = perSuit.indices.maxByOrNull { perSuit[it] } ?: return null
        // 數牌全部集中在同一花色才算染手
        return if (perSuit[best] > 0 && perSuit[best] + honors == total) best * Tiles.SUIT_SIZE else null
    }

    /** 這張牌對某位對手來說是不是筋牌（僅供 UI 說明用） */
    private fun hasSuji(tile: Int, opponent: OpponentState): Boolean {
        if (!Tiles.isSuited(tile)) return false
        val rank = Tiles.rank(tile)
        val forward = rank <= 6 && opponent.hasDiscarded(tile + 3)
        val backward = rank >= 4 && opponent.hasDiscarded(tile - 3)
        val possibleDirections = (if (rank <= 6) 1 else 0) + (if (rank >= 4) 1 else 0)
        val killed = (if (forward) 1 else 0) + (if (backward) 1 else 0)
        return killed == possibleDirections
    }

    /** 附近有沒有壁牌（做搭子需要的牌已經 4 張全部現身） */
    private fun hasWall(tile: Int, table: TableState, seen: SeenTiles): Boolean {
        if (!Tiles.isSuited(tile)) return false
        return SUIT_NEIGHBOUR_OFFSETS.any { offset ->
            val neighbour = tile + offset
            Tiles.isSuited(neighbour) &&
                Tiles.suitStart(neighbour) == Tiles.suitStart(tile) &&
                table.unseenCount(neighbour, seen) == 0
        }
    }

    private companion object {
        /** 會影響搭子的鄰牌範圍：±1、±2 */
        val SUIT_NEIGHBOUR_OFFSETS = intArrayOf(-2, -1, 1, 2)
    }
}
