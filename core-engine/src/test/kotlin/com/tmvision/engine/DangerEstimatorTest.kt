package com.tmvision.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 放槍機率模型的性質測試。
 *
 * 這裡刻意**不驗證絕對數值**——那些數字來自 `docs/wait_stats.json` 的模擬統計，
 * 重跑模擬就會微幅變動。真正該被鎖住的是模型的性質：
 * 現物比較安全、絕張不可能被胡、中張比么九危險⋯⋯這些不管統計怎麼更新都必須成立。
 *
 * 同樣的性質在 `tools/danger_model.py` 也驗證了一次（那份可以在沒有 JDK 的機器上跑）。
 */
class DangerEstimatorTest {

    private val estimator = DangerEstimator()

    /** 一個典型的中盤手牌：4 面子 + 兩面 + 眼 + 摸進一張 5 條 */
    private val myHand = HandState.of("123m456m789m123p45p11z5s")

    /**
     * 對手常見的早巡牌河：么九與字牌。
     * 刻意不含條子（讓條子的測試不受現物干擾），也不含東
     * （手上已有 2 張東，三家牌河再各出現 1 張就會超過 4 張，那是不合法的場面）。
     */
    private val typicalRiver = river("19m19p234z")

    private val fiveSou = Tiles.indexOf('s', 5)
    private val oneSou = Tiles.indexOf('s', 1)
    private val whiteDragon = Tiles.WHITE_DRAGON

    private fun river(notation: String): List<Int> {
        val counts = Tiles.parse(notation)
        return (0 until Tiles.KINDS).flatMap { tile -> List(counts[tile]) { tile } }
    }

    private fun table(
        opponents: List<OpponentState>,
        turn: Int = 12,
        myRiver: List<Int> = emptyList(),
    ) = TableState(myHand, opponents, myRiver, turn)

    private fun opponent(
        seat: Seat = Seat.RIGHT,
        river: List<Int> = typicalRiver,
        meldedSets: Int = 1,
        meldedTiles: IntArray = IntArray(Tiles.KINDS),
    ) = OpponentState(seat, river, meldedSets, meldedTiles)

    @Test
    fun `機率一定落在 0 到 1 之間`() {
        val state = table(listOf(opponent(Seat.LEFT), opponent(Seat.ACROSS), opponent(Seat.RIGHT)))
        assertEquals(emptyList(), state.inconsistencies(), "測試情境本身必須是合法場面")
        for (tile in 0 until Tiles.KINDS) {
            val risk = estimator.dealInRisk(tile, state)
            assertTrue(risk in 0.0..1.0, "${Tiles.displayName(tile)} 的放槍機率超出範圍：$risk")
        }
    }

    @Test
    fun `中張比么九危險、么九比字牌危險`() {
        val state = table(listOf(opponent()))
        val middle = estimator.dealInRisk(fiveSou, state)
        val terminal = estimator.dealInRisk(oneSou, state)
        val honor = estimator.dealInRisk(whiteDragon, state)

        assertTrue(middle > terminal, "5條($middle) 應該比 1條($terminal) 危險")
        assertTrue(terminal > honor, "1條($terminal) 應該比 白($honor) 危險")
    }

    @Test
    fun `對手打過的牌比較安全`() {
        val plain = estimator.dealInRisk(fiveSou, table(listOf(opponent())))
        val genbutsu = estimator.dealInRisk(fiveSou, table(listOf(opponent(river = typicalRiver + fiveSou))))
        assertTrue(genbutsu < plain, "現物($genbutsu) 應該比無資訊($plain) 安全")
    }

    @Test
    fun `過水規則會改變現物的安全程度`() {
        val opponents = listOf(opponent(river = typicalRiver + fiveSou))
        val taiwanRule = DangerEstimator(DangerConfig.DEFAULT)
        val furitenRule = DangerEstimator(DangerConfig.FURITEN_TABLE)

        val taiwanRisk = taiwanRule.dealInRisk(fiveSou, table(opponents))
        val furitenRisk = furitenRule.dealInRisk(fiveSou, table(opponents))
        assertTrue(
            furitenRisk < taiwanRisk,
            "整局振聽的牌桌，現物應該遠比台灣過水規則安全：$furitenRisk vs $taiwanRisk",
        )
    }

    @Test
    fun `雙筋比無筋安全`() {
        val plain = estimator.dealInRisk(fiveSou, table(listOf(opponent())))
        val suji = estimator.dealInRisk(
            fiveSou,
            table(listOf(opponent(river = typicalRiver + Tiles.indexOf('s', 2) + Tiles.indexOf('s', 8)))),
        )
        assertTrue(suji < plain, "打過 2條 與 8條 之後，5條($suji) 應該比無筋($plain) 安全")
    }

    @Test
    fun `字牌絕張才是真的安全`() {
        // 白：我手上 0 張，牌河 4 張 -> 場上一張不剩。
        // 字牌只能雙碰或單吊，兩種都要對手手上有這張牌，所以真的是 0。
        val opponents = listOf(opponent(river = typicalRiver + List(2) { whiteDragon }))
        val state = table(opponents, myRiver = List(2) { whiteDragon })

        assertEquals(0, state.unseenCount(whiteDragon), "白應該已經 4 張全部現身")
        assertEquals(0.0, estimator.dealInRisk(whiteDragon, state), "字牌絕張的放槍機率必須是 0")
    }

    @Test
    fun `數牌絕張不等於安全`() {
        // 這是最容易寫錯、後果也最嚴重的一條：
        // 5條 4 張全現身了，但對手可以握著 4條6條 等你打 5條——
        // 嵌張與兩面聽的是鄰牌，跟場上還剩幾張 5條 一點關係都沒有。
        // （3 張 5條 放在自己的牌河，所以對下家而言不是現物）
        val state = table(listOf(opponent()), myRiver = List(3) { fiveSou })

        assertEquals(0, state.unseenCount(fiveSou), "5條 應該已經 4 張全部現身")
        val exhausted = estimator.dealInRisk(fiveSou, state)
        assertTrue(exhausted > 0.0, "數牌絕張仍然可能被嵌張／兩面胡，不能報 0%")

        val normal = estimator.dealInRisk(fiveSou, table(listOf(opponent())))
        assertTrue(exhausted < normal, "但確實比一般情況安全（雙碰／單吊被排除了）：$exhausted vs $normal")
    }

    @Test
    fun `巡目越晚越危險`() {
        val early = estimator.dealInRisk(fiveSou, table(listOf(opponent()), turn = 4))
        val late = estimator.dealInRisk(fiveSou, table(listOf(opponent()), turn = 16))
        assertTrue(late > early, "第 16 巡($late) 應該比第 4 巡($early) 危險")
    }

    @Test
    fun `副露越多越危險`() {
        val concealed = estimator.dealInRisk(fiveSou, table(listOf(opponent(meldedSets = 0))))
        val melded = estimator.dealInRisk(fiveSou, table(listOf(opponent(meldedSets = 3))))
        assertTrue(melded > concealed, "副露 3 組($melded) 應該比門清($concealed) 危險")
    }

    @Test
    fun `三家一起算比單家危險`() {
        val one = estimator.dealInRisk(fiveSou, table(listOf(opponent(Seat.RIGHT))))
        val three = estimator.dealInRisk(
            fiveSou,
            table(listOf(opponent(Seat.LEFT), opponent(Seat.ACROSS), opponent(Seat.RIGHT))),
        )
        assertTrue(three > one, "三家($three) 應該比一家($one) 危險")
        assertTrue(three < one * 3, "聯合機率不是單純相加")
    }

    @Test
    fun `染手的對手在該花色特別危險`() {
        val plain = opponent(seat = Seat.ACROSS, meldedSets = 2)
        val flush = opponent(
            seat = Seat.ACROSS,
            meldedSets = 2,
            meldedTiles = Tiles.parse("111p234p"),      // 副露全是筒子
        )
        val pinTile = Tiles.indexOf('p', 5)

        val plainRisk = estimator.dealInRisk(pinTile, table(listOf(plain)))
        val flushRisk = estimator.dealInRisk(pinTile, table(listOf(flush)))
        assertTrue(flushRisk > plainRisk, "對家在收筒子時，打 5筒($flushRisk) 應該更危險($plainRisk)")
    }

    @Test
    fun `沒有對手資料時風險為零並標示出來`() {
        val state = TableState(myHand, opponents = emptyList(), turn = 10)
        assertTrue(!state.hasOpponentInfo)
        assertEquals(0.0, estimator.dealInRisk(fiveSou, state))
    }

    @Test
    fun `說明文字會指出安全的理由`() {
        val opponents = listOf(opponent(seat = Seat.RIGHT, river = typicalRiver + fiveSou))
        val breakdown = estimator.explain(fiveSou, table(opponents))

        assertEquals(fiveSou, breakdown.tile)
        assertTrue(breakdown.reasons.any { it.contains("下家") }, "應該說明是下家的現物：${breakdown.reasons}")
        assertTrue(breakdown.percentText.endsWith("%"))
    }
}
