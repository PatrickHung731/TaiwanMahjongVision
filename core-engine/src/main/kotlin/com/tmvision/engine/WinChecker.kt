package com.tmvision.engine

/** 面子/眼的種類 */
enum class GroupType(val label: String) {
    /** 眼（雀頭），2 張同樣的牌 */
    PAIR("眼"),

    /** 刻子，3 張同樣的牌 */
    TRIPLET("刻"),

    /** 順子，3 張連續的數牌 */
    SEQUENCE("順"),
}

/**
 * 一組已成形的牌。
 *
 * @property startTile 刻子與眼是該牌本身；順子是最小的那張（例如 123 筒 = 1 筒）
 */
data class TileGroup(val type: GroupType, val startTile: Int) {

    /** 這組牌實際包含的牌索引 */
    val tiles: List<Int>
        get() = when (type) {
            GroupType.PAIR -> listOf(startTile, startTile)
            GroupType.TRIPLET -> listOf(startTile, startTile, startTile)
            GroupType.SEQUENCE -> listOf(startTile, startTile + 1, startTile + 2)
        }

    /** 例如 "順:1筒2筒3筒"、"刻:東"、"眼:5條" */
    override fun toString(): String = when (type) {
        GroupType.SEQUENCE ->
            "${type.label}:${Tiles.displayName(startTile)}${Tiles.rank(startTile + 1)}${Tiles.rank(startTile + 2)}"
        else -> "${type.label}:${Tiles.displayName(startTile)}"
    }
}

/**
 * 胡牌型判定與拆解。
 *
 * 台灣 16 張的胡牌型 = **5 個面子 + 1 個眼**（副露幾組就少湊幾個面子）。
 * 這裡刻意用最直白的遞迴拆牌實作，與 [ShantenCalculator] 的 DP 完全獨立，
 * 一方面給 UI 顯示「你的牌是怎麼組成的」，一方面在單元測試裡互相驗證
 * （`shanten == -1` 必須恰好等價於 `isWinning == true`）。
 *
 * 注意：本類別只判斷**牌型結構**，不處理台數計算與役種（那是之後的計台模組）。
 */
object WinChecker {

    /** 是否為完整胡牌型（張數必須是待切狀態的 3n+2） */
    fun isWinning(hand: HandState): Boolean = decompose(hand) != null

    /**
     * 拆解胡牌型。
     *
     * @return 眼在第一個，其後為各面子；無法組成胡牌型時回傳 `null`。
     *         同一手牌可能有多種拆法（例如 1112345678999 這種），這裡回傳找到的第一組。
     */
    fun decompose(hand: HandState): List<TileGroup>? {
        if (!hand.isDrawn) return null                     // 只有 3n+2 張才可能胡
        val counts = hand.concealed.copyOf()
        for (pair in 0 until Tiles.KINDS) {
            if (counts[pair] < 2) continue
            counts[pair] -= 2
            val groups = ArrayList<TileGroup>(hand.neededMelds + 1)
            groups.add(TileGroup(GroupType.PAIR, pair))
            if (takeMelds(counts, 0, hand.neededMelds, groups)) {
                counts[pair] += 2
                return groups
            }
            counts[pair] += 2
        }
        return null
    }

    /**
     * 由索引 [start] 起，把剩下的牌全部拆成 [remaining] 個面子。
     *
     * 每次都從「最小的還有剩的牌」下手：那張牌只可能是刻子的一員，或某個順子的起點，
     * 兩種可能都試一次即可窮盡所有拆法。
     */
    private fun takeMelds(
        counts: IntArray,
        start: Int,
        remaining: Int,
        out: MutableList<TileGroup>,
    ): Boolean {
        if (remaining == 0) return counts.all { it == 0 }

        var i = start
        while (i < Tiles.KINDS && counts[i] == 0) i++
        if (i == Tiles.KINDS) return false                 // 沒牌了卻還缺面子

        // 刻子
        if (counts[i] >= 3) {
            counts[i] -= 3
            out.add(TileGroup(GroupType.TRIPLET, i))
            if (takeMelds(counts, i, remaining - 1, out)) {
                counts[i] += 3
                return true
            }
            out.removeAt(out.size - 1)
            counts[i] += 3
        }

        // 順子
        if (Tiles.canStartRun(i) && counts[i + 1] > 0 && counts[i + 2] > 0) {
            counts[i]--; counts[i + 1]--; counts[i + 2]--
            out.add(TileGroup(GroupType.SEQUENCE, i))
            if (takeMelds(counts, i, remaining - 1, out)) {
                counts[i]++; counts[i + 1]++; counts[i + 2]++
                return true
            }
            out.removeAt(out.size - 1)
            counts[i]++; counts[i + 1]++; counts[i + 2]++
        }

        return false
    }
}
