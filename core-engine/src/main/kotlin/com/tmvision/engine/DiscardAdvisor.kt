package com.tmvision.engine

import kotlin.math.pow

/**
 * 打法風格：決定「快點聽牌」與「不要放槍」之間怎麼取捨。
 *
 * @property riskWeight 放槍機率的懲罰倍率，越大越保守
 */
enum class PlayStyle(val display: String, val riskWeight: Double) {
    /** 進攻：以最快聽牌為主，只避開明顯的危險牌 */
    ATTACK("進攻", 0.4),

    /** 平衡：預設值，效率與安全並重 */
    BALANCED("平衡", 1.0),

    /** 防守：優先不放槍，寧可退回一向聽 */
    DEFENSE("防守", 3.0),
}

/**
 * 一張候選打牌的完整評估：效率 + 風險。
 *
 * @property dealInRisk 放槍機率（0.0 ~ 1.0）。沒有對手牌河資料時為 0，
 *                      請用 [TableAdvice.hasOpponentInfo] 判斷這個數字有沒有意義。
 * @property score      綜合分數，越大越推薦。純粹是排序用的相對值，沒有物理意義。
 */
data class TileRecommendation(
    val discard: Int,
    val shantenAfter: Int,
    val acceptance: List<TileCount>,
    val acceptanceTiles: Int,
    val dealInRisk: Double,
    val danger: DangerBreakdown?,
    val score: Double,
) {
    val discardName: String get() = Tiles.displayName(discard)
    val isTenpai: Boolean get() = shantenAfter == 0
    val riskPercent: String get() = "%.1f%%".format(dealInRisk * 100)

    override fun toString(): String =
        "丟$discardName →${shantenAfter}向聽, 進張 $acceptanceTiles 張, 放槍 $riskPercent"
}

/**
 * 一次完整的桌面建議，也是 AR 覆蓋層的資料來源。
 */
data class TableAdvice(
    val analysis: HandAnalysis,
    val recommendations: List<TileRecommendation>,
    val style: PlayStyle,
    val hasOpponentInfo: Boolean,
) {
    /** 綜合分數最高的選擇 */
    val best: TileRecommendation? get() = recommendations.firstOrNull()

    /** 放槍機率最低的選擇（想收手的時候看這個） */
    val safest: TileRecommendation? get() = recommendations.minByOrNull { it.dealInRisk }

    /**
     * AR 覆蓋層用的單行提示，例如：
     * ```
     * 建議丟牌：9條 (放槍 1.8%) | 聽牌進張：3筒, 6筒 (剩餘 5 張)
     * ```
     */
    fun overlayText(maxTiles: Int = 6): String {
        if (analysis.isWinning) return "🎉 已胡牌！"
        val best = best ?: return analysis.overlayText(maxTiles)

        val risk = if (hasOpponentInfo) " (放槍 ${best.riskPercent})" else ""
        val head = "建議丟牌：${best.discardName}$risk"
        if (best.acceptance.isEmpty()) return "$head | 無有效進張"
        val label = if (best.isTenpai) "聽牌進張" else "${best.shantenAfter}向聽進張"
        val tiles = best.acceptance.take(maxTiles).joinToString(", ") { it.name }
        val suffix = if (best.acceptance.size > maxTiles) "…等${best.acceptance.size}種" else ""
        return "$head | $label：$tiles$suffix (剩餘 ${best.acceptanceTiles} 張)"
    }

    /** 第二行提示：最佳解很危險時，附上一個保守選項 */
    fun alternativeText(): String? {
        if (!hasOpponentInfo) return "放槍機率：尚未辨識到牌河"
        val best = best ?: return null
        val safest = safest ?: return null
        if (safest.discard == best.discard) return null
        if (best.dealInRisk < RISK_ALERT_THRESHOLD) return null
        return "想收手：改打 ${safest.discardName} (放槍 ${safest.riskPercent}, " +
            "${safest.shantenAfter}向聽, 進張 ${safest.acceptanceTiles} 張)"
    }

    /** 開發/除錯用的多行摘要 */
    fun detailText(topN: Int = 5): String = buildString {
        val dataNote = if (hasOpponentInfo) "對手資料：有" else "對手資料：無（放槍機率不可用）"
        appendLine("向聽數：${analysis.shanten}　風格：${style.display}　$dataNote")
        recommendations.take(topN).forEachIndexed { index, item ->
            appendLine("${index + 1}. $item")
            item.danger?.reasons?.takeIf { hasOpponentInfo }?.let {
                appendLine("     ${it.joinToString("；")}")
            }
        }
    }

    private companion object {
        /** 超過這個放槍機率就主動提示保守選項 */
        const val RISK_ALERT_THRESHOLD = 0.05
    }
}

/**
 * 把「牌效率」與「放槍風險」合成最終建議。
 *
 * ```kotlin
 * val advisor = DiscardAdvisor()
 * val advice = advisor.advise(table, PlayStyle.BALANCED)
 * println(advice.overlayText())
 * // 建議丟牌：9條 (放槍 1.8%) | 聽牌進張：3筒, 6筒 (剩餘 5 張)
 * ```
 *
 * ## 分數怎麼算
 * ```
 * score = 效率分 - 風格權重 × 放槍機率 × RISK_SCALE
 * 效率分 = 0.45^向聽數 × (0.6 + 0.4 × min(1, 進張張數 / 24))
 * ```
 * 效率分的底數與 `RISK_SCALE` 是**啟發式權重**，不是統計值——
 * 它們決定的是「多冒 1% 放槍風險，值得換多少進張」，本來就沒有客觀答案，
 * 所以刻意寫成透明的公式讓人調，而不是藏在黑箱裡。
 * 真正有數據支撐的是 [DangerEstimator] 算出來的放槍機率本身。
 */
class DiscardAdvisor(
    private val engine: TaiwanMahjongEngine = TaiwanMahjongEngine(),
    private val estimator: DangerEstimator = DangerEstimator(),
) {

    /**
     * 列出所有可打的牌並排序，最推薦的在第一個。
     *
     * @param table 目前桌面狀態，手牌必須是待切狀態（未副露時 17 張）
     */
    fun recommend(table: TableState, style: PlayStyle = PlayStyle.BALANCED): List<TileRecommendation> {
        val hand = table.hand
        if (!hand.isDrawn) return emptyList()

        val seen = table.seenTiles()
        val options = engine.discardOptions(hand, seen, evaluateAllDiscards = true)

        return options.map { option ->
            val risk = if (table.hasOpponentInfo) estimator.dealInRisk(option.discard, table, seen) else 0.0
            val danger = if (table.hasOpponentInfo) estimator.explain(option.discard, table, seen) else null
            TileRecommendation(
                discard = option.discard,
                shantenAfter = option.shantenAfter,
                acceptance = option.acceptance,
                acceptanceTiles = option.acceptanceTiles,
                dealInRisk = risk,
                danger = danger,
                score = score(option, risk, style),
            )
        }.sortedWith(
            compareByDescending<TileRecommendation> { it.score }
                .thenBy { it.dealInRisk }
                .thenBy { it.shantenAfter }
                .thenBy { it.discard }
        )
    }

    /** 完整建議：向聽數分析 + 排序後的候選牌 + 覆蓋層文字 */
    fun advise(table: TableState, style: PlayStyle = PlayStyle.BALANCED): TableAdvice {
        val seen = table.seenTiles()
        return TableAdvice(
            analysis = engine.analyze(table.hand, seen),
            recommendations = recommend(table, style),
            style = style,
            hasOpponentInfo = table.hasOpponentInfo,
        )
    }

    private fun score(option: DiscardOption, risk: Double, style: PlayStyle): Double {
        val shantenValue = SHANTEN_DECAY.pow(option.shantenAfter.coerceAtLeast(0))
        val acceptanceValue = 0.6 + 0.4 * minOf(1.0, option.acceptanceTiles / ACCEPTANCE_FULL_SCALE)
        return shantenValue * acceptanceValue - style.riskWeight * risk * RISK_SCALE
    }

    private companion object {
        /** 啟發式：每多一個向聽，價值大約打 45 折 */
        const val SHANTEN_DECAY = 0.45

        /** 啟發式：進張達到這個張數就算「進張很足」 */
        const val ACCEPTANCE_FULL_SCALE = 24.0

        /** 啟發式：放槍機率換算成分數的倍率（1% 放槍 ≈ 0.03 分） */
        const val RISK_SCALE = 3.0
    }
}
