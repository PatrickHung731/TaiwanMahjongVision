package com.tmvision.engine

/** 座位（相對於自己） */
enum class Seat(val display: String) {
    /** 上家：在你的左邊，你可以吃他的牌 */
    LEFT("上家"),

    /** 對家 */
    ACROSS("對家"),

    /** 下家：在你的右邊 */
    RIGHT("下家"),
}

/**
 * 一位對手目前「看得到」的資訊。
 *
 * 這些全部都是鏡頭掃得到的東西——這就是為什麼放槍機率算得出來：
 * 對手的暗牌看不到，但他**打過什麼**、**吃碰了什麼**是攤在桌上的。
 *
 * @property river       牌河，依打出的先後順序。順序有意義：後面打的牌訊息量比較大。
 * @property meldedSets  吃碰槓的組數
 * @property meldedTiles 副露亮出來的牌（34 陣列），用來判斷他是不是在做同一花色
 */
class OpponentState(
    val seat: Seat,
    val river: List<Int> = emptyList(),
    val meldedSets: Int = 0,
    val meldedTiles: IntArray = IntArray(Tiles.KINDS),
) {

    init {
        require(meldedSets in 0..ShantenCalculator.SETS_FOR_WIN) {
            "${seat.display}的副露組數必須是 0..${ShantenCalculator.SETS_FOR_WIN}，實際為 $meldedSets"
        }
        river.forEach { require(it in 0 until Tiles.KINDS) { "牌河出現非法牌索引: $it" } }
        Tiles.requireValid(meldedTiles)
    }

    /** 這張牌他打過嗎（現物判定） */
    fun hasDiscarded(tile: Int): Boolean = tile in river

    /**
     * 他打過的牌之中，第幾巡打的（最早的那次）。
     * 早巡打的牌訊息量低（那時候還在整理牌），晚巡打的牌代表他真的不要。
     */
    fun firstDiscardTurn(tile: Int): Int = river.indexOf(tile).let { if (it < 0) -1 else it + 1 }

    /** 這位對手看得到的牌（牌河 + 副露），用來累計已見牌 */
    fun visibleTiles(): IntArray {
        val counts = meldedTiles.copyOf()
        for (tile in river) {
            if (counts[tile] < Tiles.MAX_PER_KIND) counts[tile]++
        }
        return counts
    }

    override fun toString(): String =
        "${seat.display}(副露${meldedSets}組, 牌河${river.size}張)"
}

/**
 * 整桌的狀態：自己的手牌 + 三家的牌河與副露。
 *
 * 這是 Phase 2 影像辨識的輸出格式，也是 [DiscardAdvisor] 的輸入。
 * 只辨識得到自己手牌時（沒把整桌拍進去），`opponents` 傳空 list，
 * 引擎會照樣給效率建議，但放槍機率會標示為「資料不足」。
 *
 * @property turn 巡目（自己摸了第幾張牌）。台灣 16 張一局約 16~18 巡。
 */
class TableState(
    val hand: HandState,
    val opponents: List<OpponentState> = emptyList(),
    val myRiver: List<Int> = emptyList(),
    val turn: Int = 1,
) {

    init {
        require(turn >= 1) { "巡目至少從 1 開始，實際為 $turn" }
        require(opponents.map { it.seat }.toSet().size == opponents.size) { "同一個座位重複出現" }
    }

    /** 有沒有對手資訊可以用來算放槍機率 */
    val hasOpponentInfo: Boolean get() = opponents.isNotEmpty()

    /**
     * 場上所有「我看得到、但不在我手上」的牌：四家牌河 + 所有副露。
     * 拿來算某張牌還剩幾張沒出現。
     */
    fun seenTiles(): SeenTiles {
        val counts = IntArray(Tiles.KINDS)
        for (tile in myRiver) {
            if (counts[tile] < Tiles.MAX_PER_KIND) counts[tile]++
        }
        for (opponent in opponents) {
            val visible = opponent.visibleTiles()
            for (tile in 0 until Tiles.KINDS) {
                counts[tile] = minOf(Tiles.MAX_PER_KIND, counts[tile] + visible[tile])
            }
        }
        return SeenTiles(counts)
    }

    /**
     * 某種牌「還沒現身」的張數：4 - 我手上的 - 場上看得到的。
     * 這是對手可能握有的張數上限，危險度計算會用到。
     */
    fun unseenCount(tile: Int, seen: SeenTiles = seenTiles()): Int =
        (Tiles.MAX_PER_KIND - hand.concealed[tile] - seen.count(tile)).coerceAtLeast(0)

    /**
     * 檢查場面有沒有矛盾：同一種牌總共出現超過 4 張，或手牌張數不合法。
     *
     * **Phase 2 一定要用這個把關**：影像辨識最常見的失敗就是把一張牌認錯或重複計算，
     * 而算牌引擎照樣會給出一個看起來很有自信的建議。寧可跳過這一幀顯示「請對準牌桌」，
     * 也不要拿錯的牌面去算——使用者不會知道那個建議是垃圾。
     *
     * @return 問題描述清單；空清單代表場面合法
     */
    fun inconsistencies(): List<String> {
        val problems = ArrayList<String>()
        val seen = seenTiles()
        for (tile in 0 until Tiles.KINDS) {
            val total = hand.concealed[tile] + seen.count(tile)
            if (total > Tiles.MAX_PER_KIND) {
                problems.add("${Tiles.displayName(tile)} 共出現 $total 張（最多 ${Tiles.MAX_PER_KIND} 張）")
            }
        }
        if (!hand.isValidSize) {
            problems.add(
                "手牌 ${hand.tileCount} 張不合法（副露 ${hand.meldedSets} 組時應為 " +
                    "${hand.restSize} 或 ${hand.restSize + 1} 張）"
            )
        }
        return problems
    }

    /** 場面是否合法，可以放心拿去算建議 */
    val isConsistent: Boolean get() = inconsistencies().isEmpty()
}
