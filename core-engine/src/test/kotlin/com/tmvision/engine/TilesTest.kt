package com.tmvision.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 34 位元牌型索引的基礎測試。
 * 這層寫錯的話，後面所有辨識結果與算牌都會歪掉，所以邊界要測清楚。
 */
class TilesTest {

    @Test
    fun `索引對應到正確的牌面`() {
        assertEquals("1萬", Tiles.displayName(0))
        assertEquals("9萬", Tiles.displayName(8))
        assertEquals("1筒", Tiles.displayName(9))
        assertEquals("9筒", Tiles.displayName(17))
        assertEquals("1條", Tiles.displayName(18))
        assertEquals("9條", Tiles.displayName(26))
        assertEquals("東", Tiles.displayName(27))
        assertEquals("白", Tiles.displayName(33))
    }

    @Test
    fun `indexOf 與 displayName 互為反向`() {
        for (index in 0 until Tiles.KINDS) {
            val suit = when {
                index < Tiles.PIN_START -> 'm'
                index < Tiles.SOU_START -> 'p'
                index < Tiles.HONOR_START -> 's'
                else -> 'z'
            }
            assertEquals(index, Tiles.indexOf(suit, Tiles.rank(index)))
        }
    }

    @Test
    fun `順子起點只能是數牌的 1 到 7`() {
        for (index in 0 until Tiles.KINDS) {
            val expected = Tiles.isSuited(index) && Tiles.rank(index) <= 7
            assertEquals(expected, Tiles.canStartRun(index), Tiles.displayName(index))
        }
        assertFalse(Tiles.canStartRun(7), "8萬 不能當順子起點（會跨到筒）")
        assertFalse(Tiles.canStartRun(Tiles.EAST), "字牌沒有順子")
        assertTrue(Tiles.canStartRun(6), "7萬 可以組 789萬")
    }

    @Test
    fun `parse 解析 MPSZ 記法`() {
        val counts = Tiles.parse("123m456p789s11z")
        assertEquals(1, counts[Tiles.indexOf('m', 1)])
        assertEquals(1, counts[Tiles.indexOf('p', 6)])
        assertEquals(1, counts[Tiles.indexOf('s', 9)])
        assertEquals(2, counts[Tiles.EAST])
        assertEquals(11, counts.sum())
    }

    @Test
    fun `parse 容許空白與分隔符號`() {
        assertTrue(Tiles.parse("123m 456p, 11z").contentEquals(Tiles.parse("123m456p11z")))
    }

    @Test
    fun `parse 與 format 互為反向`() {
        // format 輸出的是「同花色合併並排序」的正規形式
        assertEquals("123456789m123456p11z", Tiles.format(Tiles.parse("123m456m789m123p456p11z")))
        assertEquals("1111223344556677z", Tiles.format(Tiles.parse("1234567z1234567z11z")))

        // 正規化之後再解析，必須得到完全相同的 34 陣列
        val notations = listOf(
            "123m456m789m123p456p11z",
            "1122334455667788m",
            "1234567z1234567z11z",
            "45p11z",
            "",
        )
        for (notation in notations) {
            val counts = Tiles.parse(notation)
            assertTrue(
                Tiles.parse(Tiles.format(counts)).contentEquals(counts),
                "round-trip 失敗：$notation → ${Tiles.format(counts)}",
            )
        }
    }

    @Test
    fun `非法記法會被擋下`() {
        assertFailsWith<IllegalArgumentException> { Tiles.parse("123") }        // 沒有花色
        assertFailsWith<IllegalArgumentException> { Tiles.parse("8z") }         // 字牌只有 1..7
        assertFailsWith<IllegalArgumentException> { Tiles.parse("0m") }         // 沒有 0 萬
        assertFailsWith<IllegalArgumentException> { Tiles.parse("11111m") }     // 同一種牌超過 4 張
        assertFailsWith<IllegalArgumentException> { Tiles.parse("123x") }       // 未知花色
    }

    @Test
    fun `么九牌判定`() {
        assertTrue(Tiles.isTerminalOrHonor(Tiles.indexOf('m', 1)))
        assertTrue(Tiles.isTerminalOrHonor(Tiles.indexOf('s', 9)))
        assertTrue(Tiles.isTerminalOrHonor(Tiles.WHITE_DRAGON))
        assertFalse(Tiles.isTerminalOrHonor(Tiles.indexOf('p', 5)))
    }

    @Test
    fun `describe 輸出中文牌面`() {
        assertEquals("1萬 2萬 3萬 東 東", Tiles.describe(Tiles.parse("123m11z")))
    }

    @Test
    fun `手牌張數與狀態判定`() {
        val rest = HandState.of("123m456m789m123p45p11z")
        assertTrue(rest.isRest)
        assertFalse(rest.isDrawn)
        assertEquals(16, rest.tileCount)

        val drawn = rest.draw(Tiles.indexOf('s', 9))
        assertTrue(drawn.isDrawn)
        assertEquals(17, drawn.tileCount)
        assertEquals(16, rest.tileCount, "draw 不能改動原本的手牌")

        val melded = HandState.of("123m456m78p11z", meldedSets = 2)
        assertTrue(melded.isRest)
        assertEquals(10, melded.tileCount)
        assertEquals(3, melded.neededMelds)
    }

    @Test
    fun `已見牌計算剩餘張數`() {
        val hand = HandState.of("123m456m789m123p45p11z")
        val seen = SeenTiles.of("33p")
        assertEquals(1, seen.remaining(Tiles.indexOf('p', 3), hand), "4 - 手上1 - 已見2")
        assertEquals(4, seen.remaining(Tiles.indexOf('p', 6), hand))
        assertEquals(2, SeenTiles.EMPTY.remaining(Tiles.EAST, hand), "手上已有 2 張東")
    }
}
