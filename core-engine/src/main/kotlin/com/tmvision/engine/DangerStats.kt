package com.tmvision.engine

/**
 * 台灣 16 張的危險度統計表。
 *
 * ## 這個檔案是自動產生的，不要手改
 * 數字來自 `tools/generate_wait_stats.py` 的蒙地卡羅模擬（2000 手），
 * 要更新請重跑：
 * ```
 * python tools/generate_wait_stats.py --hands 5000
 * python tools/emit_danger_stats.py
 * ```
 *
 * ## 為什麼要自己跑模擬
 * 網路上找得到的危險度統計全部是日本麻將（4 面子 + 1 眼、13 張）的數據。
 * 台灣是 5 面子 + 1 眼、16 張，牌型結構不同——例如台灣的雙碰聽比例高得多
 * （面子多、對子自然多），直接套日麻數據會系統性低估對子系的危險。
 *
 * ## 已知限制
 * 模擬中只有一家在打，沒有吃碰槓與他家搶牌，貪心打法也比真人差，
 * 所以這是**先驗分布**而不是真實牌譜統計。有實戰資料時應該重新校正。
 * 詳見 `docs/DANGER_MODEL.md`。
 */
object DangerStats {

    /** 模擬手數 */
    const val SIMULATED_HANDS = 2000

    /** 其中真的聽牌的手數 */
    const val TENPAI_HANDS = 988

    /** 平均每個聽牌手牌聽幾種牌——機率正規化的錨點 */
    const val AVERAGE_WAIT_KINDS = 1.65

    /**
     * 前幾巡的模擬聽牌率是 0，但真實牌局早巡照樣可能有人聽牌（只是罕見）。
     * 顯示 0% 會讓人誤以為絕對安全，所以給一個下限。
     */
    const val MIN_TENPAI_RATE = 0.01

    /** 第 n 巡的累積聽牌率（索引 0 = 第 1 巡） */
    private val TENPAI_RATE_BY_TURN = doubleArrayOf(
        0.0, 0.0, 0.001, 0.0065, 0.0165, 0.0305,   // 第 1~6 巡
        0.0525, 0.076, 0.105, 0.132, 0.1695, 0.2115,   // 第 7~12 巡
        0.2615, 0.31, 0.3595, 0.4075, 0.4525, 0.494,   // 第 13~18 巡
    )

    /** 數牌 1~9 成為胡牌張的相對頻率（1.0 = 最危險的那個點數） */
    private val DANGER_BY_RANK = doubleArrayOf(
        0.238, 0.498, 0.833, 0.967, 1.0, 0.981, 0.792, 0.428, 0.23,   // 一 二 三 四 五 六 七 八 九
    )

    /** 字牌的相對頻率 */
    private const val HONOR_DANGER = 0.038

    /** 數牌 1~9 的胡牌張之中，屬於「兩面聽」的比例（決定筋牌能折抵多少） */
    private val RYANMEN_SHARE_BY_RANK = doubleArrayOf(
        0.812, 0.612, 0.344, 0.396, 0.487, 0.47, 0.329, 0.617, 0.79,   // 一 二 三 四 五 六 七 八 九
    )

    /** 字牌沒有順子，兩面聽比例必為 0 */
    private const val HONOR_RYANMEN_SHARE = 0.0

    /**
     * 數牌 1~9 的胡牌張之中，屬於「對子系」（雙碰 + 單吊）的比例。
     *
     * 這個區分很重要：對子系聽牌需要對手**手上真的握有那張牌**，
     * 所以「這張還剩幾張沒現身」只能折抵這個比例；
     * 兩面／嵌張／邊張他握的是鄰牌，就算這張牌 4 張全現身，照樣會被胡。
     */
    private val PAIR_WAIT_SHARE_BY_RANK = doubleArrayOf(
        0.188, 0.276, 0.321, 0.281, 0.19, 0.189, 0.366, 0.243, 0.21,   // 一 二 三 四 五 六 七 八 九
    )

    /** 字牌只能雙碰或單吊 */
    private const val HONOR_PAIR_WAIT_SHARE = 1.0

    /** 34 種牌的基礎權重總和，用來把相對頻率換算成機率 */
    val BASE_WEIGHT_TOTAL: Double =
        (0 until Tiles.KINDS).sumOf { baseWeight(it) }

    /** 第 [turn] 巡時，對手已經聽牌的機率（門清、未修正副露） */
    fun tenpaiRateByTurn(turn: Int): Double {
        val index = (turn - 1).coerceIn(0, TENPAI_RATE_BY_TURN.size - 1)
        return maxOf(TENPAI_RATE_BY_TURN[index], MIN_TENPAI_RATE)
    }

    /** 這種牌成為胡牌張的相對頻率 */
    fun baseWeight(tile: Int): Double =
        if (Tiles.isHonor(tile)) HONOR_DANGER else DANGER_BY_RANK[Tiles.rank(tile) - 1]

    /** 這種牌的胡牌張之中，屬於兩面聽的比例 */
    fun ryanmenShare(tile: Int): Double =
        if (Tiles.isHonor(tile)) HONOR_RYANMEN_SHARE else RYANMEN_SHARE_BY_RANK[Tiles.rank(tile) - 1]

    /** 屬於對子系（雙碰 + 單吊）的比例 */
    fun pairWaitShare(tile: Int): Double =
        if (Tiles.isHonor(tile)) HONOR_PAIR_WAIT_SHARE else PAIR_WAIT_SHARE_BY_RANK[Tiles.rank(tile) - 1]

    /** 屬於嵌張 + 邊張的比例（剩下的那一塊） */
    fun closedWaitShare(tile: Int): Double =
        (1.0 - ryanmenShare(tile) - pairWaitShare(tile)).coerceAtLeast(0.0)
}
