package com.tmvision.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 向聽數計算的測試。
 *
 * 所有期望值都由 `tools/verify_engine.py` 產生並經過三重驗證：
 * 最大重疊 DP、傳統面子分解公式、以及完全不含公式的暴力窮舉真值。
 */
class ShantenCalculatorTest {

    private val calculator = ShantenCalculator()

    private data class Fixture(
        val description: String,
        val notation: String,
        val meldedSets: Int,
        val expected: Int,
    )

    @Test
    fun `基準測資 - 台灣 16 張向聽數`() {
        val fixtures = listOf(
            // ---- 未副露：17 張（待切）與 16 張（待摸）----
            Fixture("完整胡牌 5面子+1眼", "123m456m789m123p456p11z", 0, -1),
            Fixture("單吊聽牌", "123m456m789m123p456p1z", 0, 0),
            Fixture("兩面聽牌", "123m456m789m123p45p11z", 0, 0),
            Fixture("嵌張聽牌", "123m456m789m123p46p11z", 0, 0),
            Fixture("邊張聽牌", "123m456m789m123p12p11z", 0, 0),
            Fixture("雙碰聽牌", "123m456m789m123p55p11z", 0, 0),
            Fixture("五面子無眼(單吊型)", "123m456m789m123p456p9s", 0, 0),
            Fixture("一向聽", "123m456m789m123p45p12z", 0, 1),
            Fixture("二向聽", "123m456m789m12p45p12z9s", 0, 2),
            // ---- 極端牌型 ----
            Fixture("同花色八對子(順子更划算)", "1122334455667788m", 0, 0),
            Fixture("么九對子(完全沒有順子可用)", "1199m1199p1199s1122z", 0, 4),
            Fixture("全字牌爛牌", "1234567z1234567z11z", 0, 3),
            // ---- 副露後：每副露 1 組就少湊 1 個面子、手牌少 3 張 ----
            Fixture("副露1組 + 13張(待摸)", "123m456m789m45p11z", 1, 0),
            Fixture("副露1組 + 14張(待切)", "123m456m789m45p11z9s", 1, 0),
            Fixture("副露2組 + 10張", "123m456m78p11z", 2, 0),
            Fixture("副露3組 + 7張", "123m45m11z", 3, 0),
            Fixture("副露4組 + 4張", "45m11z", 4, 0),
        )

        for (fixture in fixtures) {
            val hand = HandState.of(fixture.notation, fixture.meldedSets)
            assertEquals(
                fixture.expected,
                calculator.shanten(hand),
                "${fixture.description}（${fixture.notation}, 副露${fixture.meldedSets}組）",
            )
        }
    }

    @Test
    fun `胡牌型的向聽數必為 -1`() {
        val winningHands = listOf(
            "123m456m789m123p456p11z",      // 五順子
            "111m222m333m444m555m66m",      // 五刻子
            "123m123m123m123m11122p",       // 重複順子 + 刻子
            "111222333444555z66z",          // 全字牌（東東東 南南南 西西西 北北北 中中中 發發）
            "123456789m123p456p11s",        // 一色到底 + 眼
        )
        for (notation in winningHands) {
            val hand = HandState.of(notation)
            assertEquals(ShantenCalculator.HAND_SIZE_DRAWN, hand.tileCount, "測資張數應為 17：$notation")
            assertEquals(-1, calculator.shanten(hand), "應為胡牌型：$notation")
            assertTrue(WinChecker.isWinning(hand), "WinChecker 也應判定為胡牌：$notation")
        }
    }

    @Test
    fun `摸一張牌後向聽數不會變差`() {
        val random = Random(20260728)
        repeat(300) {
            val hand = randomHand(ShantenCalculator.HAND_SIZE_REST, random)
            val before = calculator.shanten(hand, 0)
            for (tile in 0 until Tiles.KINDS) {
                if (hand[tile] >= Tiles.MAX_PER_KIND) continue
                hand[tile]++
                val after = calculator.shanten(hand, 0)
                hand[tile]--
                assertTrue(
                    after == before || after == before - 1,
                    "摸 ${Tiles.displayName(tile)} 後向聽數只能持平或減 1：$before → $after（${Tiles.format(hand)}）",
                )
            }
        }
    }

    @Test
    fun `打一張牌後向聽數不會變好`() {
        val random = Random(11111)
        repeat(300) {
            val hand = randomHand(ShantenCalculator.HAND_SIZE_DRAWN, random)
            val before = calculator.shanten(hand, 0)
            for (tile in 0 until Tiles.KINDS) {
                if (hand[tile] == 0) continue
                hand[tile]--
                val after = calculator.shanten(hand, 0)
                hand[tile]++
                assertTrue(after >= before, "打牌不可能讓向聽數變好：$before → $after")
            }
        }
    }

    @Test
    fun `shanten 為 -1 等價於 WinChecker 判定胡牌`() {
        val random = Random(555)
        repeat(4000) {
            val counts = randomHand(ShantenCalculator.HAND_SIZE_DRAWN, random)
            val hand = HandState(counts)
            assertEquals(
                WinChecker.isWinning(hand),
                calculator.shanten(hand) == -1,
                "胡牌判定不一致：${Tiles.format(counts)}",
            )
        }
    }

    @Test
    fun `副露後手牌變少但向聽數定義一致`() {
        // 同樣的「4 面子 + 1 搭子 + 1 眼」骨架，副露幾組結果都該是聽牌
        assertEquals(0, calculator.shanten(HandState.of("123m456m789m123p45p11z", 0)))
        assertEquals(0, calculator.shanten(HandState.of("456m789m123p45p11z", 1)))
        assertEquals(0, calculator.shanten(HandState.of("789m123p45p11z", 2)))
        assertEquals(0, calculator.shanten(HandState.of("123p45p11z", 3)))
        assertEquals(0, calculator.shanten(HandState.of("45p11z", 4)))
        // 五組全副露時只剩眼，摸到什麼都可以單吊
        assertEquals(0, calculator.shanten(HandState.of("5p", 5)))
        assertEquals(-1, calculator.shanten(HandState.of("55p", 5)))
    }

    @Test
    fun `1 FPS 預算內可完成大量計算`() {
        val hand = HandState.of("123m456m789m123p45p11z9s")
        repeat(50) { calculator.shanten(hand) }                       // 暖機

        val iterations = 2000
        val startedAt = System.nanoTime()
        repeat(iterations) { calculator.shanten(hand) }
        val perCallMicros = (System.nanoTime() - startedAt) / 1_000.0 / iterations

        println("單次向聽數計算平均 ${"%.1f".format(perCallMicros)} µs")
        assertTrue(
            perCallMicros < 2_000,
            "單次向聽數計算應遠低於 2 ms，實際 ${"%.1f".format(perCallMicros)} µs",
        )
    }

    /** 隨機發一手合法的牌（每種牌最多 4 張） */
    private fun randomHand(size: Int, random: Random): IntArray {
        val counts = IntArray(Tiles.KINDS)
        var dealt = 0
        while (dealt < size) {
            val tile = random.nextInt(Tiles.KINDS)
            if (counts[tile] < Tiles.MAX_PER_KIND) {
                counts[tile]++
                dealt++
            }
        }
        return counts
    }
}
