package com.tmvision.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 胡牌型判定與拆解。
 * 這支是 [ShantenCalculator] 的獨立對照組：兩者用完全不同的演算法，結論必須一致。
 */
class WinCheckerTest {

    @Test
    fun `五面子加一眼即為胡牌`() {
        assertTrue(WinChecker.isWinning(HandState.of("123m456m789m123p456p11z")))
        assertTrue(WinChecker.isWinning(HandState.of("111m222m333m444m555m66m")))
        assertTrue(WinChecker.isWinning(HandState.of("111222333444555z66z")))
    }

    @Test
    fun `四面子加一眼在台灣麻將不算胡`() {
        // 這是日本麻將的 14 張胡牌型，台灣 16 張還差一個面子
        val japanese = HandState.of("123m456m789m123p11z")
        assertFalse(japanese.isDrawn, "14 張不是台灣 16 張的合法張數")
        assertFalse(WinChecker.isWinning(japanese))
    }

    @Test
    fun `張數不對就不可能胡牌`() {
        assertFalse(WinChecker.isWinning(HandState.of("123m456m789m123p45p11z")), "16 張是待摸狀態")
        assertNull(WinChecker.decompose(HandState.of("123m456m789m123p45p11z")))
    }

    @Test
    fun `沒有眼就不算胡`() {
        // 5 個面子 + 兩張湊不成眼的牌
        assertFalse(WinChecker.isWinning(HandState.of("123m456m789m123p456p12z")))
    }

    @Test
    fun `拆解結果包含一個眼與五個面子`() {
        val groups = assertNotNull(WinChecker.decompose(HandState.of("123m456m789m123p456p11z")))
        assertEquals(6, groups.size)
        assertEquals(1, groups.count { it.type == GroupType.PAIR })
        assertEquals(5, groups.count { it.type != GroupType.PAIR })
        assertEquals(17, groups.sumOf { it.tiles.size })
        assertEquals(GroupType.PAIR, groups.first().type, "眼固定排在第一個")
    }

    @Test
    fun `拆解會處理刻子與順子混合的牌型`() {
        val groups = assertNotNull(WinChecker.decompose(HandState.of("111123456m111234p55p")))
        assertEquals(6, groups.size)
        assertEquals(17, groups.sumOf { it.tiles.size })
        // 還原成 34 陣列後必須與原手牌一致
        val restored = IntArray(Tiles.KINDS)
        groups.flatMap { it.tiles }.forEach { restored[it]++ }
        assertTrue(restored.contentEquals(Tiles.parse("111123456m111234p55p")))
    }

    @Test
    fun `副露之後只需要湊剩下的面子`() {
        val hand = HandState.of("123m456m789m11z", meldedSets = 2)   // 副露 2 組 + 11 張
        assertTrue(hand.isDrawn)
        assertTrue(WinChecker.isWinning(hand))

        val groups = assertNotNull(WinChecker.decompose(hand))
        assertEquals(4, groups.size, "3 個暗面子 + 1 個眼（另外 2 組已副露）")
    }

    @Test
    fun `槓也只算一個面子`() {
        // 暗槓 1 萬（4 張牌離開手牌）＋ 其餘 4 個面子 + 眼
        val hand = HandState.of("456m789m123p456p11z", meldedSets = 1)
        assertEquals(14, hand.tileCount)
        assertTrue(hand.isDrawn)
        assertTrue(WinChecker.isWinning(hand))
    }
}
