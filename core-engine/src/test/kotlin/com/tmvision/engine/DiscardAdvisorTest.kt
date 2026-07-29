package com.tmvision.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 效率 + 風險合成建議的測試。
 */
class DiscardAdvisorTest {

    private val advisor = DiscardAdvisor()

    private fun river(notation: String): List<Int> {
        val counts = Tiles.parse(notation)
        return (0 until Tiles.KINDS).flatMap { tile -> List(counts[tile]) { tile } }
    }

    /**
     * 中盤情境：4 面子 + 兩面 + 眼，剛摸進一張 5 條。
     * 只有打掉 5 條才能聽牌，但 5 條是中張，對三家都很危險。
     */
    private fun midGameTable(turn: Int = 14) = TableState(
        hand = HandState.of("123m456m789m123p45p11z5s"),
        opponents = listOf(
            OpponentState(Seat.LEFT, river("19m1z2z9p"), meldedSets = 1),
            OpponentState(Seat.ACROSS, river("1m9s5z6z2p"), meldedSets = 2, meldedTiles = Tiles.parse("111p234p")),
            OpponentState(Seat.RIGHT, river("9m9p3z4z7z"), meldedSets = 1, meldedTiles = Tiles.parse("789s")),
        ),
        myRiver = river("1z9m"),
        turn = turn,
    )

    @Test
    fun `防守風格選的牌一定不會比進攻風格危險`() {
        val table = midGameTable()
        val attack = assertNotNull(advisor.recommend(table, PlayStyle.ATTACK).firstOrNull())
        val defense = assertNotNull(advisor.recommend(table, PlayStyle.DEFENSE).firstOrNull())

        assertTrue(
            defense.dealInRisk <= attack.dealInRisk,
            "防守($defense) 不該比進攻($attack) 危險",
        )
        assertTrue(
            attack.shantenAfter <= defense.shantenAfter,
            "進攻($attack) 的向聽數不該比防守($defense) 差",
        )
    }

    @Test
    fun `進攻風格會選擇聽牌`() {
        val best = assertNotNull(advisor.recommend(midGameTable(), PlayStyle.ATTACK).firstOrNull())
        assertEquals(0, best.shantenAfter, "進攻模式應該直接進聽牌")
        assertEquals("5條", best.discardName, "只有打 5條 能聽牌")
    }

    @Test
    fun `所有候選牌都會被評估並排序`() {
        val table = midGameTable()
        val recommendations = advisor.recommend(table, PlayStyle.BALANCED)

        assertEquals(16, recommendations.size, "這手 17 張牌有 16 種不同的牌可打")
        recommendations.zipWithNext { a, b ->
            assertTrue(a.score >= b.score, "分數必須由高到低排序：$a 之後是 $b")
        }
        assertTrue(recommendations.all { it.dealInRisk in 0.0..1.0 })
    }

    @Test
    fun `最安全的選項與最佳選項可以分開查`() {
        val advice = advisor.advise(midGameTable(), PlayStyle.BALANCED)
        val safest = assertNotNull(advice.safest)
        val best = assertNotNull(advice.best)

        assertTrue(
            safest.dealInRisk <= best.dealInRisk,
            "safest($safest) 的放槍機率必須是所有選項裡最低的",
        )
        assertEquals(
            advice.recommendations.minOf { it.dealInRisk },
            safest.dealInRisk,
        )
    }

    @Test
    fun `覆蓋層文字包含放槍機率`() {
        val advice = advisor.advise(midGameTable(), PlayStyle.BALANCED)
        val text = advice.overlayText()

        assertContains(text, "建議丟牌：")
        assertContains(text, "放槍")
        assertContains(text, "%")
        println(text)
        println(advice.alternativeText() ?: "(無保守提示)")
    }

    @Test
    fun `沒有牌河資料時退回純效率建議並說明原因`() {
        val table = TableState(hand = HandState.of("123m456m789m123p45p11z5s"), turn = 8)
        val advice = advisor.advise(table, PlayStyle.BALANCED)

        assertTrue(!advice.hasOpponentInfo)
        assertTrue(advice.recommendations.all { it.dealInRisk == 0.0 })
        assertEquals("5條", advice.best?.discardName, "沒有風險資料時就純看效率")
        assertTrue(!advice.overlayText().contains("放槍"), "不該顯示假的放槍機率")
        assertContains(advice.alternativeText() ?: "", "尚未辨識到牌河")
    }

    @Test
    fun `測試情境本身必須是合法場面`() {
        // 這條測試是在保護其他測試：如果情境裡某種牌超過 4 張，
        // 算出來的機率就沒有意義，而且不會有任何東西報錯。
        assertEquals(emptyList(), midGameTable().inconsistencies())
    }

    @Test
    fun `辨識出矛盾的場面時要抓得出來`() {
        val broken = TableState(
            hand = HandState.of("123m456m789m123p45p11z5s"),
            opponents = listOf(
                // 我手上有 2 張東，牌河再出現 3 張 -> 總共 5 張，不可能
                OpponentState(Seat.RIGHT, List(3) { Tiles.EAST }),
            ),
            turn = 10,
        )
        assertTrue(!broken.isConsistent)
        assertContains(broken.inconsistencies().first(), "東")
    }

    @Test
    fun `胡牌時直接顯示胡牌`() {
        val table = TableState(hand = HandState.of("123m456m789m123p456p11z"), turn = 12)
        assertEquals("🎉 已胡牌！", advisor.advise(table).overlayText())
    }

    @Test
    fun `待摸狀態沒有切牌建議但仍有進張分析`() {
        val table = TableState(hand = HandState.of("123m456m789m123p45p11z"), turn = 10)
        val advice = advisor.advise(table)

        assertTrue(advice.recommendations.isEmpty(), "16 張不需要切牌")
        assertEquals(0, advice.analysis.shanten)
        assertContains(advice.overlayText(), "聽牌")
    }

    @Test
    fun `分析效能符合 1 FPS 預算`() {
        val table = midGameTable()
        repeat(10) { advisor.advise(table) }              // 暖機

        val iterations = 30
        val startedAt = System.nanoTime()
        repeat(iterations) { advisor.advise(table) }
        val perCallMillis = (System.nanoTime() - startedAt) / 1_000_000.0 / iterations

        println("單次 advise（含放槍機率）平均 ${"%.2f".format(perCallMillis)} ms")
        assertTrue(perCallMillis < 150, "每秒只跑一次，單次必須遠低於 150 ms，實際 $perCallMillis ms")
    }
}
