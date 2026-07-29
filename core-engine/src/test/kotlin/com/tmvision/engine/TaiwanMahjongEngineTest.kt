package com.tmvision.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 引擎對外行為測試：有效進張、切牌建議、已見牌扣除、AR 覆蓋層文字。
 */
class TaiwanMahjongEngineTest {

    private val engine = TaiwanMahjongEngine()

    // ------------------------------------------------------------------
    // 有效進張 / 聽牌
    // ------------------------------------------------------------------

    @Test
    fun `兩面聽牌 - 聽 3筒與 6筒`() {
        val hand = HandState.of("123m456m789m123p45p11z")   // 16 張
        val waits = engine.acceptance(hand)

        assertEquals(listOf("3筒", "6筒"), waits.map { it.name })
        assertEquals(3, waits.first { it.name == "3筒" }.count, "自己手上有 1 張 3 筒，只剩 3 張")
        assertEquals(4, waits.first { it.name == "6筒" }.count)
        assertEquals(7, waits.sumOf { it.count })
    }

    @Test
    fun `單吊聽牌 - 五面子等一個眼`() {
        val hand = HandState.of("123m456m789m123p456p9s")
        assertEquals(0, engine.shanten(hand))
        assertEquals(listOf(TileCount(Tiles.indexOf('s', 9), 3)), engine.acceptance(hand))
    }

    @Test
    fun `雙碰聽牌 - 聽兩種牌各剩 2 張`() {
        val hand = HandState.of("123m456m789m123p55p11z")
        val waits = engine.acceptance(hand)
        assertEquals(listOf("5筒", "東"), waits.map { it.name })
        assertTrue(waits.all { it.count == 2 }, "各自手上已有 2 張，場上只剩 2 張")
    }

    @Test
    fun `牌河已見牌會從進張張數中扣除`() {
        val hand = HandState.of("123m456m789m123p45p11z")
        val seen = SeenTiles.of("33p6p")                    // 已見 2 張 3 筒、1 張 6 筒
        val waits = engine.acceptance(hand, seen)

        assertEquals(1, waits.first { it.name == "3筒" }.count, "4 - 手上1 - 已見2 = 1")
        assertEquals(3, waits.first { it.name == "6筒" }.count, "4 - 已見1 = 3")
        assertEquals(4, waits.sumOf { it.count })
    }

    @Test
    fun `進張已被打光時不會列入`() {
        val hand = HandState.of("123m456m789m123p46p11z")   // 嵌張聽 5 筒
        assertEquals(listOf("5筒"), engine.acceptance(hand).map { it.name })

        val seen = SeenTiles.of("5555p")                    // 4 張 5 筒全在牌河
        assertTrue(engine.acceptance(hand, seen).isEmpty(), "聽的牌被打光就不該列出")
    }

    // ------------------------------------------------------------------
    // 切牌建議
    // ------------------------------------------------------------------

    @Test
    fun `17 張 - 建議打掉孤張進入聽牌`() {
        val hand = HandState.of("123m456m789m123p45p11z9s")  // 摸進 9 條
        val best = assertNotNull(engine.discardOptions(hand).firstOrNull())

        assertEquals("9條", best.discardName)
        assertEquals(0, best.shantenAfter)
        assertTrue(best.isTenpai)
        assertEquals(listOf("3筒", "6筒"), best.acceptance.map { it.name })
        assertEquals(7, best.acceptanceTiles)
    }

    @Test
    fun `切牌建議會考慮牌河 - 進張少的聽牌不再是最佳解`() {
        val hand = HandState.of("123m456m789m123p45p11z9s")
        val seen = SeenTiles.of("33p")                       // 3 筒已見 2 張
        val best = assertNotNull(engine.discardOptions(hand, seen).firstOrNull())

        assertEquals("9條", best.discardName)
        assertEquals(5, best.acceptanceTiles, "3筒 只剩 1 張 + 6筒 4 張")
    }

    @Test
    fun `切出去的牌本身也算已見牌`() {
        val hand = HandState.of("123m456m789m123p55p11z9s")
        val options = engine.discardOptions(hand, evaluateAllDiscards = true)
        val discardFivePin = assertNotNull(options.firstOrNull { it.discardName == "5筒" })

        val backToFivePin = assertNotNull(discardFivePin.acceptance.firstOrNull { it.name == "5筒" })
        assertEquals(
            2,
            backToFivePin.count,
            "4 張 - 手上剩 1 張 - 剛打掉的 1 張 = 2 張",
        )
    }

    @Test
    fun `副露後同樣能給切牌建議`() {
        val hand = HandState.of("123m456m789m45p11z9s", meldedSets = 1)   // 14 張
        assertTrue(hand.isDrawn)

        val best = assertNotNull(engine.discardOptions(hand).firstOrNull())
        assertEquals("9條", best.discardName)
        assertEquals(0, best.shantenAfter)
        assertEquals(8, best.acceptanceTiles, "3筒、6筒 各 4 張")
    }

    @Test
    fun `切牌建議依向聽數與進張數排序`() {
        val hand = HandState.of("123m456m789m123p45p11z9s")
        val options = engine.discardOptions(hand, evaluateAllDiscards = true)

        assertEquals(options.minOf { it.shantenAfter }, options.first().shantenAfter, "最佳解要排第一個")
        assertEquals(16, options.size, "這手 17 張牌共有 16 種不同的牌可以打")
        options.zipWithNext { a, b ->
            assertTrue(
                a.shantenAfter < b.shantenAfter ||
                    (a.shantenAfter == b.shantenAfter && a.acceptanceTiles >= b.acceptanceTiles),
                "排序錯誤：$a 應排在 $b 之前",
            )
        }
    }

    // ------------------------------------------------------------------
    // analyze 與 UI 文字
    // ------------------------------------------------------------------

    @Test
    fun `analyze 待切狀態產生 AR 覆蓋層文字`() {
        val hand = HandState.of("123m456m789m123p45p11z9s")
        val analysis = engine.analyze(hand, SeenTiles.of("33p"))

        assertEquals(0, analysis.shanten)
        assertEquals(17, analysis.handSize)
        assertEquals("建議丟牌：9條 | 聽牌進張：3筒, 6筒 (剩餘 5 張)", analysis.overlayText())
    }

    @Test
    fun `analyze 待摸狀態顯示進張`() {
        val hand = HandState.of("123m456m789m123p45p11z")
        val analysis = engine.analyze(hand)

        assertTrue(analysis.isTenpai)
        assertTrue(analysis.discardOptions.isEmpty(), "16 張不需要切牌")
        assertEquals("聽牌！進張：3筒, 6筒 (剩餘 7 張)", analysis.overlayText())
    }

    @Test
    fun `analyze 一向聽會標示向聽數`() {
        val hand = HandState.of("123m456m789m123p45p12z")
        val analysis = engine.analyze(hand)

        assertEquals(1, analysis.shanten)
        assertContains(analysis.overlayText(), "1向聽")
    }

    @Test
    fun `analyze 胡牌`() {
        val hand = HandState.of("123m456m789m123p456p11z")
        val analysis = engine.analyze(hand)

        assertEquals(-1, analysis.shanten)
        assertTrue(analysis.isWinning)
        assertEquals("🎉 已胡牌！", analysis.overlayText())

        val groups = assertNotNull(engine.decompose(hand))
        assertEquals(6, groups.size, "台灣 16 張＝5 面子 + 1 眼")
        assertEquals(1, groups.count { it.type == GroupType.PAIR })
    }

    @Test
    fun `analyze 效能符合 1 FPS 預算`() {
        val hand = HandState.of("1112345678999m123p9s")      // 拆法極多、分支最複雜的牌型
        assertTrue(hand.isDrawn)
        repeat(20) { engine.analyze(hand) }                  // 暖機

        val iterations = 50
        val startedAt = System.nanoTime()
        repeat(iterations) { engine.analyze(hand) }
        val perAnalysisMillis = (System.nanoTime() - startedAt) / 1_000_000.0 / iterations

        println("單次 analyze 平均 ${"%.2f".format(perAnalysisMillis)} ms")
        assertTrue(
            perAnalysisMillis < 100,
            "每秒只跑一次，單次分析必須遠低於 100 ms，實際 ${"%.2f".format(perAnalysisMillis)} ms",
        )
    }

    // ------------------------------------------------------------------
    // 錯誤處理：辨識結果張數不對時要清楚報錯，不能默默算出垃圾建議
    // ------------------------------------------------------------------

    @Test
    fun `張數不合法時丟出明確錯誤`() {
        val fifteen = HandState.of("123m456m789m123p456p")   // 15 張
        val error = assertFailsWith<IllegalArgumentException> { engine.analyze(fifteen) }
        assertContains(error.message ?: "", "手牌張數不合法")
    }

    @Test
    fun `狀態不對的呼叫會被擋下`() {
        val drawn = HandState.of("123m456m789m123p45p11z9s")   // 17 張
        val rest = HandState.of("123m456m789m123p45p11z")      // 16 張

        assertFailsWith<IllegalArgumentException> { engine.acceptance(drawn) }
        assertFailsWith<IllegalArgumentException> { engine.discardOptions(rest) }
    }
}
