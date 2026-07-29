package com.tmvision.engine

/**
 * 牌面索引工具。
 *
 * 全系統統一以「長度 34 的 IntArray」表示任何一組牌（手牌、牌河、已見牌），
 * 陣列值 = 該種牌的張數（0..4）：
 *
 * ```
 *  索引 0  ~ 8   一萬 ~ 九萬   (m)
 *  索引 9  ~ 17  一筒 ~ 九筒   (p)
 *  索引 18 ~ 26  一條 ~ 九條   (s)
 *  索引 27 ~ 33  東 南 西 北 中 發 白 (z)
 * ```
 *
 * 花牌（春夏秋冬梅蘭菊竹）不佔 34 陣列，另以 [HandState.flowers] 計數，
 * 因為花牌不參與 16 張的面子組合，只影響最後計台。
 */
object Tiles {

    /** 牌的種類數（不含花牌） */
    const val KINDS = 34

    /** 每種牌在一副牌中的張數 */
    const val MAX_PER_KIND = 4

    const val MAN_START = 0     // 萬
    const val PIN_START = 9     // 筒
    const val SOU_START = 18    // 條
    const val HONOR_START = 27  // 字牌

    /** 每個花色的牌數（萬筒條各 9 種） */
    const val SUIT_SIZE = 9

    /** 東南西北中發白 */
    const val EAST = 27
    const val SOUTH = 28
    const val WEST = 29
    const val NORTH = 30
    const val RED_DRAGON = 31   // 中
    const val GREEN_DRAGON = 32 // 發
    const val WHITE_DRAGON = 33 // 白

    private val SUIT_LABELS = arrayOf("萬", "筒", "條")
    private val HONOR_LABELS = arrayOf("東", "南", "西", "北", "中", "發", "白")
    private val SUIT_CODES = charArrayOf('m', 'p', 's')

    /** 是否為數牌（萬筒條）；false 代表字牌 */
    fun isSuited(index: Int): Boolean = index in 0 until HONOR_START

    /** 是否為字牌 */
    fun isHonor(index: Int): Boolean = index >= HONOR_START

    /** 數牌回傳 1..9；字牌回傳 1..7 */
    fun rank(index: Int): Int = if (isSuited(index)) index % SUIT_SIZE + 1 else index - HONOR_START + 1

    /** 是否為么九牌（一、九、字牌） */
    fun isTerminalOrHonor(index: Int): Boolean =
        isHonor(index) || rank(index) == 1 || rank(index) == 9

    /**
     * 此牌能否作為「順子的起點」。
     *
     * 只有數牌的 1~7 可以起頭（789 是最後一組），字牌沒有順子。
     * 因為 8、9 一定回傳 false，順子永遠不會跨花色，這點是 [ShantenCalculator] 的重要前提。
     */
    fun canStartRun(index: Int): Boolean = isSuited(index) && index % SUIT_SIZE <= SUIT_SIZE - 3

    /** 取得該牌所屬花色的第一個索引（字牌回傳 [HONOR_START]） */
    fun suitStart(index: Int): Int = if (isSuited(index)) index / SUIT_SIZE * SUIT_SIZE else HONOR_START

    /** 中文牌名，例如 "3筒"、"東" */
    fun displayName(index: Int): String {
        require(index in 0 until KINDS) { "牌索引超出範圍: $index" }
        return if (isHonor(index)) HONOR_LABELS[index - HONOR_START]
        else "${rank(index)}${SUIT_LABELS[index / SUIT_SIZE]}"
    }

    /** 由花色代號與點數取得索引，例如 indexOf('p', 3) = 11（3筒） */
    fun indexOf(suit: Char, rank: Int): Int = when (suit) {
        'm' -> requireRank(rank, 9) + MAN_START
        'p' -> requireRank(rank, 9) + PIN_START
        's' -> requireRank(rank, 9) + SOU_START
        'z' -> requireRank(rank, 7) + HONOR_START
        else -> throw IllegalArgumentException("未知花色代號: $suit（僅支援 m/p/s/z）")
    }

    private fun requireRank(rank: Int, max: Int): Int {
        require(rank in 1..max) { "點數超出範圍: $rank（此花色為 1..$max）" }
        return rank - 1
    }

    /**
     * 解析 MPSZ 記法字串成 34 陣列。
     *
     * 例：`"123m456p789s11z"` → 1~3萬、4~6筒、7~9條、東東。
     * 允許空白、逗號與底線做為分隔，方便測資排版。
     */
    fun parse(notation: String): IntArray {
        val counts = IntArray(KINDS)
        val pending = ArrayList<Int>(14)
        for (ch in notation) {
            when {
                ch.isDigit() -> pending.add(ch - '0')
                ch == 'm' || ch == 'p' || ch == 's' || ch == 'z' -> {
                    require(pending.isNotEmpty()) { "記法中的 '$ch' 前面沒有數字" }
                    for (rank in pending) counts[indexOf(ch, rank)]++
                    pending.clear()
                }
                ch == ' ' || ch == ',' || ch == '-' || ch == '_' -> Unit
                else -> throw IllegalArgumentException("無法解析的字元: '$ch'")
            }
        }
        require(pending.isEmpty()) { "記法結尾缺少花色代號 m/p/s/z" }
        for (i in 0 until KINDS) {
            require(counts[i] <= MAX_PER_KIND) { "${displayName(i)} 超過 $MAX_PER_KIND 張" }
        }
        return counts
    }

    /** 34 陣列 → MPSZ 記法（parse 的反向操作） */
    fun format(counts: IntArray): String {
        val sb = StringBuilder()
        for (suit in 0..2) {
            val head = sb.length
            for (i in suit * SUIT_SIZE until suit * SUIT_SIZE + SUIT_SIZE) {
                repeat(counts[i]) { sb.append(rank(i)) }
            }
            if (sb.length > head) sb.append(SUIT_CODES[suit])
        }
        val head = sb.length
        for (i in HONOR_START until KINDS) repeat(counts[i]) { sb.append(rank(i)) }
        if (sb.length > head) sb.append('z')
        return sb.toString()
    }

    /** 34 陣列 → 中文牌面列表，例如 "1萬 2萬 3萬 東 東" */
    fun describe(counts: IntArray): String = buildString {
        for (i in 0 until KINDS) repeat(counts[i]) {
            if (isNotEmpty()) append(' ')
            append(displayName(i))
        }
    }

    /** 檢查是否為合法的 34 陣列 */
    fun requireValid(counts: IntArray) {
        require(counts.size == KINDS) { "牌陣列長度必須是 $KINDS，實際為 ${counts.size}" }
        for (i in 0 until KINDS) {
            require(counts[i] in 0..MAX_PER_KIND) {
                "${displayName(i)} 張數不合法: ${counts[i]}（必須是 0..$MAX_PER_KIND）"
            }
        }
    }
}

/**
 * 花牌。不進入 34 陣列，只在計台時使用。
 */
enum class Flower(val display: String) {
    SPRING("春"), SUMMER("夏"), AUTUMN("秋"), WINTER("冬"),
    PLUM("梅"), ORCHID("蘭"), CHRYSANTHEMUM("菊"), BAMBOO("竹");

    companion object {
        /** 一副牌共 8 張花牌 */
        const val COUNT = 8
    }
}
