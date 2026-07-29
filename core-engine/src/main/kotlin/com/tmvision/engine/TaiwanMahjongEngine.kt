package com.tmvision.engine

/**
 * 台灣 16 張麻將的算牌引擎（Phase 1 對外唯一入口）。
 *
 * 純 Kotlin 實作，**不依賴任何 Android API**，因此可以：
 * - 在 JVM 上用單元測試驗證（不需要模擬器）
 * - 之後被 Phase 2 的 AI 辨識管線直接呼叫（背景執行緒、1 FPS）
 *
 * ```kotlin
 * val engine = TaiwanMahjongEngine()
 * val hand = HandState.of("123m456m789m123p45p11z9s")     // 17 張，剛摸進 9 條
 * val seen = SeenTiles.of("33p")                          // 牌河已見 2 張 3 筒
 * println(engine.analyze(hand, seen).overlayText())
 * // 建議切牌：9條 | 聽牌進張：3筒, 6筒 (剩餘 5 張)
 * ```
 *
 * @param calculator 向聽數計算器。預設取用目前執行緒專屬的實例；
 *                   若在自己的背景執行緒建立引擎，直接用預設值即可。
 */
class TaiwanMahjongEngine(
    private val calculator: ShantenCalculator = ShantenCalculator.forCurrentThread(),
) {

    /** 目前向聽數：`-1` 已胡、`0` 聽牌、`n` 還差 n 步 */
    fun shanten(hand: HandState): Int = calculator.shanten(hand.concealed, hand.meldedSets)

    /** 是否已經胡牌（5 面子 + 1 眼） */
    fun isWinning(hand: HandState): Boolean = WinChecker.isWinning(hand)

    /** 拆解目前牌型，供 UI 顯示；無法胡牌時回傳 null */
    fun decompose(hand: HandState): List<TileGroup>? = WinChecker.decompose(hand)

    /**
     * **待摸狀態（16 張）** 的有效進張：摸到哪些牌可以讓向聽數再減 1。
     * 聽牌時（向聽 0）回傳的就是「聽的牌」。
     *
     * @param seen 牌河等已見牌，用來把剩餘張數算準
     * @return 依牌序排列的 (牌, 剩餘張數)；剩餘張數已扣掉自己手上與已見的
     */
    fun acceptance(hand: HandState, seen: SeenTiles = SeenTiles.EMPTY): List<TileCount> {
        require(hand.isRest) {
            "有效進張需要待摸狀態（${hand.restSize} 張），實際為 ${hand.tileCount} 張"
        }
        return acceptanceOf(hand.concealed.copyOf(), hand.meldedSets, seen)
    }

    /**
     * **待切狀態（17 張）** 的切牌建議，最好的排在第一個。
     *
     * 排序規則：向聽數小 → 進張總張數多 → 進張種類多 → 優先打掉價值低的牌（字牌、么九）。
     *
     * @param evaluateAllDiscards `false`（預設）只評估能達到最低向聽數的切法，速度最快，
     *                            足夠 AR 覆蓋層使用；`true` 會把每一張都算完整進張，
     *                            適合除錯或做牌譜分析。
     */
    fun discardOptions(
        hand: HandState,
        seen: SeenTiles = SeenTiles.EMPTY,
        evaluateAllDiscards: Boolean = false,
    ): List<DiscardOption> {
        require(hand.isDrawn) {
            "切牌建議需要待切狀態（${hand.restSize + 1} 張），實際為 ${hand.tileCount} 張"
        }

        val work = hand.concealed.copyOf()

        // 第一輪：先算出每一種切法的向聽數，找出最好的向聽數
        val shantenByDiscard = IntArray(Tiles.KINDS) { UNUSED }
        var minShanten = Int.MAX_VALUE
        for (tile in 0 until Tiles.KINDS) {
            if (work[tile] == 0) continue
            work[tile]--
            val value = calculator.shanten(work, hand.meldedSets)
            work[tile]++
            shantenByDiscard[tile] = value
            if (value < minShanten) minShanten = value
        }

        // 第二輪：只對值得看的切法計算有效進張（進張分析比向聽數貴 34 倍）
        val options = ArrayList<DiscardOption>(Tiles.KINDS)
        for (tile in 0 until Tiles.KINDS) {
            val after = shantenByDiscard[tile]
            if (after == UNUSED) continue
            if (!evaluateAllDiscards && after > minShanten) continue

            work[tile]--
            // 打出去的那張牌已經在自己的牌河裡，等於「已見」，剩餘張數要再扣 1
            val acceptance = acceptanceOf(work.copyOf(), hand.meldedSets, seen, justDiscarded = tile)
            work[tile]++

            options.add(
                DiscardOption(
                    discard = tile,
                    shantenAfter = after,
                    acceptance = acceptance,
                    acceptanceTiles = acceptance.sumOf { it.count },
                )
            )
        }

        options.sortWith(DISCARD_ORDER)
        return options
    }

    /**
     * 完整分析：自動判斷目前是待摸（16 張）還是待切（17 張）狀態。
     * 這是相機管線每秒呼叫一次的入口。
     */
    fun analyze(hand: HandState, seen: SeenTiles = SeenTiles.EMPTY): HandAnalysis {
        hand.requireValidSize()
        val startedAt = System.nanoTime()

        val shanten = shanten(hand)
        val winning = shanten == -1
        val acceptance = if (hand.isRest && !winning) acceptance(hand, seen) else emptyList()
        val discards = if (hand.isDrawn && !winning) discardOptions(hand, seen) else emptyList()

        return HandAnalysis(
            shanten = shanten,
            handSize = hand.tileCount,
            meldedSets = hand.meldedSets,
            isWinning = winning,
            acceptance = acceptance,
            discardOptions = discards,
            computeNanos = System.nanoTime() - startedAt,
        )
    }

    /**
     * 有效進張的實際計算。
     *
     * @param work          可以被本函式暫時改動的工作陣列（呼叫端已複製）
     * @param justDiscarded 剛打出去的那張牌（已進入牌河，剩餘張數要再扣 1）；[NONE] 表示沒有
     */
    private fun acceptanceOf(
        work: IntArray,
        meldedSets: Int,
        seen: SeenTiles,
        justDiscarded: Int = NONE,
    ): List<TileCount> {
        val base = calculator.shanten(work, meldedSets)
        if (base < 0) return emptyList()                      // 已經胡了，沒有進張可言

        val result = ArrayList<TileCount>(Tiles.KINDS)
        for (tile in 0 until Tiles.KINDS) {
            if (work[tile] >= Tiles.MAX_PER_KIND) continue    // 4 張都在自己手上，摸不到第 5 張
            work[tile]++
            val improved = calculator.shanten(work, meldedSets) < base
            work[tile]--
            if (!improved) continue

            val inRiver = seen.count(tile) + if (tile == justDiscarded) 1 else 0
            val remaining = (Tiles.MAX_PER_KIND - work[tile] - inRiver).coerceAtLeast(0)
            if (remaining > 0) result.add(TileCount(tile, remaining))
        }
        return result
    }

    private companion object {
        /** 手上沒有這張牌，不可能打出去 */
        const val UNUSED = Int.MIN_VALUE

        /** 沒有「剛打出的牌」 */
        const val NONE = -1

        /**
         * 切牌好壞的排序規則。
         *
         * 前三項是客觀數據；最後的 [keepValue] 是主觀取捨——同樣進張數時，
         * 台灣麻將習慣先打掉字牌與么九（比較不容易再組成順子，也比較安全）。
         */
        val DISCARD_ORDER: Comparator<DiscardOption> =
            compareBy<DiscardOption> { it.shantenAfter }
                .thenByDescending { it.acceptanceTiles }
                .thenByDescending { it.acceptanceKinds }
                .thenBy { keepValue(it.discard) }
                .thenBy { it.discard }

        /** 留牌價值：數字越小越該先打掉 */
        fun keepValue(tile: Int): Int = when {
            Tiles.isHonor(tile) -> 0
            Tiles.rank(tile) == 1 || Tiles.rank(tile) == 9 -> 1
            Tiles.rank(tile) == 2 || Tiles.rank(tile) == 8 -> 2
            else -> 3
        }
    }
}
