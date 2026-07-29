package com.tmvision.engine

/**
 * 台灣 16 張麻將的向聽數（Shanten）計算器。
 *
 * ## 台灣規則
 * 胡牌型固定為「**5 個面子 + 1 個眼**」＝ 17 張（手牌 16 張 + 胡的那張）。
 * 每吃/碰/槓一組（副露）就少算一個面子，手牌張數同步減少 3 張。
 *
 * ## 演算法：最大重疊 DP（max-overlap DP）
 * 常見的日麻寫法是「面子 + 搭子」分解公式，配上一堆邊界修正（區塊上限、沒眼要 +1…），
 * 推廣到 5 面子時很容易在邊界出錯。這裡改用一個**定義上就正確**的作法：
 *
 * > 令 W 為任一「合法胡牌型」（needed 個面子 + 1 個眼，且每種牌不超過 4 張）。
 * > 從手牌變成 W，需要摸進的張數 = |W| - Σ min(W[i], hand[i])。
 * > 每一巡摸一張打一張，最後一張就是胡牌的那張，
 * > 因此 **向聽數 = (|W| - 最大重疊) - 1**，取所有 W 的最小值。
 *
 * 所以問題化簡成「求手牌與所有合法胡牌型的最大重疊張數」，用 DP 由索引 0 掃到 33 即可。
 *
 * DP 狀態 `(i, meldsLeft, pairUsed, a, b)`：
 * - `i`         目前處理到的牌索引
 * - `meldsLeft` 還需要幾個面子
 * - `pairUsed`  眼是否已經放好
 * - `a`         由 `i-2` 起始、仍覆蓋到 `i` 的順子數
 * - `b`         由 `i-1` 起始、仍覆蓋到 `i` 的順子數
 *
 * 在索引 `i` 上決定：放幾組刻子（0 或 1）、由 `i` 起始幾個順子、是否把眼放在這裡；
 * 此時 W 在 `i` 上的張數 `used = 3*刻子 + 2*眼 + 順子 + a + b` 必須 ≤ 4（合法牌數上限），
 * 對重疊的貢獻是 `min(used, hand[i])`。
 *
 * 順子只能由 1~7 起頭（見 [Tiles.canStartRun]），所以順子永遠不會跨花色，
 * 進入下一個花色時 `a`、`b` 必定是 0，不需要額外處理花色邊界。
 *
 * ## 正確性
 * `tools/verify_engine.py` 用三種互相獨立的方法交叉驗證過（DP、傳統面子分解公式、
 * 以及完全不含公式的暴力窮舉真值），在數千手隨機牌上結果完全一致。
 *
 * ## 執行緒安全
 * 本類別內部有可重複使用的 memo 緩衝區，**不是執行緒安全的**。
 * 請用 [forCurrentThread] 取得目前執行緒專屬的實例（AI 推論在背景執行緒、UI 在主執行緒各一份）。
 */
class ShantenCalculator {

    /** memo 值 */
    private val memoValue = IntArray(MEMO_SIZE)

    /** memo 世代戳記，避免每次計算都要把 10500 格清空 */
    private val memoStamp = IntArray(MEMO_SIZE)
    private var stamp = 0

    /** 目前計算中的手牌（只讀不改） */
    private var counts: IntArray = IntArray(Tiles.KINDS)

    /**
     * 計算向聽數。
     *
     * @return `-1` 已胡牌、`0` 聽牌、`n` 距離聽牌還要 n 步。
     */
    fun shanten(hand: HandState): Int = shanten(hand.concealed, hand.meldedSets)

    /**
     * 計算向聽數。
     *
     * @param concealed  長度 34 的暗牌陣列
     * @param meldedSets 已副露的面子數（吃/碰/槓各算 1 組），0..5
     */
    fun shanten(concealed: IntArray, meldedSets: Int = 0): Int {
        require(meldedSets in 0..SETS_FOR_WIN) { "副露面子數必須是 0..$SETS_FOR_WIN，實際為 $meldedSets" }
        val needed = SETS_FOR_WIN - meldedSets
        val overlap = maxOverlap(concealed, needed)
        return (needed * 3 + 2) - overlap - 1
    }

    /**
     * 手牌與「最接近的合法胡牌型」的最大重疊張數。
     *
     * @param neededMelds 還需要湊出的面子數（未副露時為 5）
     */
    fun maxOverlap(concealed: IntArray, neededMelds: Int): Int {
        require(concealed.size == Tiles.KINDS) { "牌陣列長度必須是 ${Tiles.KINDS}，實際為 ${concealed.size}" }
        require(neededMelds in 0..SETS_FOR_WIN) { "面子數必須是 0..$SETS_FOR_WIN，實際為 $neededMelds" }
        counts = concealed
        if (++stamp == Int.MAX_VALUE) {          // 戳記溢位時重置（實務上不會發生）
            stamp = 1
            memoStamp.fill(0)
        }
        return search(0, neededMelds, 0, 0, 0)
    }

    /**
     * DP 主體。回傳由索引 `i` 之後所能取得的最大重疊張數，[INFEASIBLE] 代表此狀態無法組成合法胡牌型。
     */
    private fun search(i: Int, meldsLeft: Int, pairUsed: Int, a: Int, b: Int): Int {
        if (i == Tiles.KINDS) {
            // 掃完 34 種牌：面子與眼都要剛好用完，且不能有沒收尾的順子
            return if (meldsLeft == 0 && pairUsed == 1 && a == 0 && b == 0) 0 else INFEASIBLE
        }

        val key = ((((i * (SETS_FOR_WIN + 1) + meldsLeft) * 2 + pairUsed) * RUN_STATES + a) * RUN_STATES) + b
        if (memoStamp[key] == stamp) return memoValue[key]

        val have = counts[i]
        val maxRuns = if (Tiles.canStartRun(i)) minOf(meldsLeft, Tiles.MAX_PER_KIND) else 0
        var best = INFEASIBLE

        // triplet: 在 i 放 0 或 1 組刻子（同一種牌只有 4 張，放不下兩組刻子）
        for (triplet in 0..minOf(1, meldsLeft)) {
            // runs: 由 i 起始的順子數
            for (runs in 0..maxRuns) {
                if (triplet + runs > meldsLeft) break
                // pair: 是否把唯一的眼放在 i
                for (pair in 0..1) {
                    if (pair == 1 && pairUsed == 1) continue
                    val used = 3 * triplet + 2 * pair + runs + a + b
                    if (used > Tiles.MAX_PER_KIND) continue      // 目標牌型不可能有 5 張同樣的牌

                    val sub = search(i + 1, meldsLeft - triplet - runs, pairUsed or pair, b, runs)
                    if (sub < 0) continue                        // 該分支無法收尾

                    val value = sub + if (used < have) used else have
                    if (value > best) best = value
                }
            }
        }

        memoStamp[key] = stamp
        memoValue[key] = best
        return best
    }

    companion object {
        /** 台灣 16 張：胡牌需要 5 個面子 */
        const val SETS_FOR_WIN = 5

        /** 未副露時的手牌張數（待摸狀態） */
        const val HAND_SIZE_REST = SETS_FOR_WIN * 3 + 1        // 16

        /** 未副露時摸牌後的張數（待切狀態） */
        const val HAND_SIZE_DRAWN = SETS_FOR_WIN * 3 + 2       // 17

        /** DP 分支無解 */
        private const val INFEASIBLE = -1

        /** a、b 兩個「順子延續數」的取值範圍 0..4 */
        private const val RUN_STATES = Tiles.MAX_PER_KIND + 1

        private const val MEMO_SIZE =
            (Tiles.KINDS + 1) * (SETS_FOR_WIN + 1) * 2 * RUN_STATES * RUN_STATES

        private val threadLocal = ThreadLocal.withInitial { ShantenCalculator() }

        /**
         * 取得目前執行緒專屬的計算器。
         * 相機推論執行緒與 UI 執行緒各持有一份，彼此不會互相干擾 memo 緩衝區。
         */
        fun forCurrentThread(): ShantenCalculator = threadLocal.get()
    }
}
