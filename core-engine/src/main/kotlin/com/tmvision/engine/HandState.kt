package com.tmvision.engine

/**
 * 一副台灣 16 張麻將的手牌狀態。
 *
 * @property concealed  暗牌（尚未副露的手牌），長度 34 的張數陣列，見 [Tiles]。
 * @property meldedSets 已副露的面子數：吃、碰、明槓、暗槓各算 **1 組**。
 *                      槓雖然用掉 4 張牌，但在組合上仍只是 1 個面子，
 *                      因此槓的那 4 張牌**不要**留在 [concealed] 裡。
 * @property flowers    已補的花牌張數（0..8）。花牌不參與 16 張的組合，只在計台時使用。
 *
 * ## 張數規則
 * - 待摸狀態（3n+1）：`concealed` 張數 = (5 - meldedSets) * 3 + 1，未副露時就是 16 張。
 * - 待切狀態（3n+2）：剛摸進或吃碰後尚未打出，張數再 +1，未副露時就是 17 張。
 */
class HandState(
    val concealed: IntArray,
    val meldedSets: Int = 0,
    val flowers: Int = 0,
) {

    init {
        Tiles.requireValid(concealed)
        require(meldedSets in 0..ShantenCalculator.SETS_FOR_WIN) {
            "副露面子數必須是 0..${ShantenCalculator.SETS_FOR_WIN}，實際為 $meldedSets"
        }
        require(flowers in 0..Flower.COUNT) { "花牌張數必須是 0..${Flower.COUNT}，實際為 $flowers" }
    }

    /** 暗牌總張數 */
    val tileCount: Int get() = concealed.sum()

    /** 還需要湊出的面子數（未副露 = 5） */
    val neededMelds: Int get() = ShantenCalculator.SETS_FOR_WIN - meldedSets

    /** 待摸狀態應有的張數（未副露 = 16） */
    val restSize: Int get() = neededMelds * 3 + 1

    /** 待摸狀態：等著摸下一張牌 */
    val isRest: Boolean get() = tileCount == restSize

    /** 待切狀態：剛摸進一張，要決定打哪張（未副露 = 17 張） */
    val isDrawn: Boolean get() = tileCount == restSize + 1

    /** 張數是否合法（待摸或待切其中之一） */
    val isValidSize: Boolean get() = isRest || isDrawn

    /** 某種牌在暗牌中的張數 */
    operator fun get(tile: Int): Int = concealed[tile]

    /** 摸進一張牌，回傳新的手牌狀態（原物件不變） */
    fun draw(tile: Int): HandState {
        require(concealed[tile] < Tiles.MAX_PER_KIND) { "${Tiles.displayName(tile)} 已經有 4 張了" }
        val next = concealed.copyOf()
        next[tile]++
        return HandState(next, meldedSets, flowers)
    }

    /** 打出一張牌，回傳新的手牌狀態（原物件不變） */
    fun discard(tile: Int): HandState {
        require(concealed[tile] > 0) { "手上沒有 ${Tiles.displayName(tile)}，無法打出" }
        val next = concealed.copyOf()
        next[tile]--
        return HandState(next, meldedSets, flowers)
    }

    /** 檢查張數是否符合台灣 16 張規則，不符合就丟出清楚的錯誤訊息 */
    fun requireValidSize() {
        require(isValidSize) {
            "手牌張數不合法：副露 $meldedSets 組時應為 $restSize 張（待摸）或 ${restSize + 1} 張（待切），" +
                "實際為 $tileCount 張 —— ${Tiles.format(concealed)}"
        }
    }

    /** 複製一份，可覆寫部分欄位 */
    fun copy(
        concealed: IntArray = this.concealed.copyOf(),
        meldedSets: Int = this.meldedSets,
        flowers: Int = this.flowers,
    ): HandState = HandState(concealed, meldedSets, flowers)

    override fun toString(): String = buildString {
        append(Tiles.format(concealed))
        if (meldedSets > 0) append(" +副露${meldedSets}組")
        if (flowers > 0) append(" +花${flowers}張")
        append(" (${tileCount}張)")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandState) return false
        return meldedSets == other.meldedSets &&
            flowers == other.flowers &&
            concealed.contentEquals(other.concealed)
    }

    override fun hashCode(): Int =
        (concealed.contentHashCode() * 31 + meldedSets) * 31 + flowers

    companion object {
        /**
         * 由 MPSZ 記法建立手牌，例如：
         * ```
         * HandState.of("123m456m789m123p456p11z")          // 17 張，待切
         * HandState.of("123m456m78p11z", meldedSets = 2)   // 副露 2 組 + 10 張，待摸
         * ```
         */
        fun of(notation: String, meldedSets: Int = 0, flowers: Int = 0): HandState =
            HandState(Tiles.parse(notation), meldedSets, flowers)
    }
}

/**
 * 「已見牌」——除了自己暗牌以外，場上所有看得到的牌：
 * 四家的牌河（海底棄牌）、他家副露、自己打出去的牌。
 *
 * 用途是把進張張數算準：某張牌的**剩餘張數 = 4 - 已見 - 自己手上有的**。
 * 這正是 AI 影像辨識最有價值的地方：人腦記不住整個牌河，鏡頭可以。
 */
class SeenTiles(val counts: IntArray = IntArray(Tiles.KINDS)) {

    init {
        Tiles.requireValid(counts)
    }

    /** 登記一張已見牌 */
    fun mark(tile: Int, amount: Int = 1): SeenTiles {
        val next = counts.copyOf()
        next[tile] = (next[tile] + amount).coerceAtMost(Tiles.MAX_PER_KIND)
        return SeenTiles(next)
    }

    /** 這種牌已經看到幾張 */
    fun count(tile: Int): Int = counts[tile]

    /**
     * 這種牌還有幾張可能被摸到。
     *
     * @param hand 自己的手牌（手上那幾張當然也摸不到了）
     */
    fun remaining(tile: Int, hand: HandState): Int =
        (Tiles.MAX_PER_KIND - counts[tile] - hand.concealed[tile]).coerceAtLeast(0)

    override fun toString(): String = "已見: ${Tiles.describe(counts)}"

    companion object {
        /** 什麼都還沒看到（牌河為空） */
        val EMPTY = SeenTiles()

        /** 由 MPSZ 記法建立，例如 `SeenTiles.of("33p9s")` 代表牌河已見 2 張 3 筒與 1 張 9 條 */
        fun of(notation: String): SeenTiles = SeenTiles(Tiles.parse(notation))
    }
}
