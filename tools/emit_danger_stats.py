#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
emit_danger_stats.py -- 由 docs/wait_stats.json 產生 Kotlin 的 DangerStats.kt

統計表是模擬跑出來的數字，手抄到 Kotlin 很容易出錯，所以直接生成。
**不要手動編輯 DangerStats.kt**，要改就重跑模擬再跑這支：

    python tools/generate_wait_stats.py --hands 5000
    python tools/emit_danger_stats.py
"""

import json
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
STATS_PATH = ROOT / "docs" / "wait_stats.json"
OUT_PATH = ROOT / "core-engine" / "src" / "main" / "kotlin" / "com" / "tmvision" / "engine" / "DangerStats.kt"

TEMPLATE = '''package com.tmvision.engine

/**
 * 台灣 16 張的危險度統計表。
 *
 * ## 這個檔案是自動產生的，不要手改
 * 數字來自 `tools/generate_wait_stats.py` 的蒙地卡羅模擬（{hands} 手），
 * 要更新請重跑：
 * ```
 * python tools/generate_wait_stats.py --hands 5000
 * python tools/emit_danger_stats.py
 * ```
 *
 * ## 為什麼要自己跑模擬
 * 網路上找得到的危險度統計全部是日本麻將（4 面子 + 1 眼、13 張）的數據。
 * 台灣是 5 面子 + 1 眼、16 張，牌型結構不同——例如台灣的雙碰聽比例高得多
 * （面子多、對子自然多），直接套日麻數據會系統性低估對子系的危險。
 *
 * ## 已知限制
 * 模擬中只有一家在打，沒有吃碰槓與他家搶牌，貪心打法也比真人差，
 * 所以這是**先驗分布**而不是真實牌譜統計。有實戰資料時應該重新校正。
 * 詳見 `docs/DANGER_MODEL.md`。
 */
object DangerStats {{

    /** 模擬手數 */
    const val SIMULATED_HANDS = {hands}

    /** 其中真的聽牌的手數 */
    const val TENPAI_HANDS = {tenpai_hands}

    /** 平均每個聽牌手牌聽幾種牌——機率正規化的錨點 */
    const val AVERAGE_WAIT_KINDS = {average_waits}

    /**
     * 前幾巡的模擬聽牌率是 0，但真實牌局早巡照樣可能有人聽牌（只是罕見）。
     * 顯示 0% 會讓人誤以為絕對安全，所以給一個下限。
     */
    const val MIN_TENPAI_RATE = 0.01

    /** 第 n 巡的累積聽牌率（索引 0 = 第 1 巡） */
    private val TENPAI_RATE_BY_TURN = doubleArrayOf(
{tenpai_rows}
    )

    /** 數牌 1~9 成為胡牌張的相對頻率（1.0 = 最危險的那個點數） */
    private val DANGER_BY_RANK = doubleArrayOf(
{danger_rows}
    )

    /** 字牌的相對頻率 */
    private const val HONOR_DANGER = {honor_danger}

    /** 數牌 1~9 的胡牌張之中，屬於「兩面聽」的比例（決定筋牌能折抵多少） */
    private val RYANMEN_SHARE_BY_RANK = doubleArrayOf(
{ryanmen_rows}
    )

    /** 字牌沒有順子，兩面聽比例必為 0 */
    private const val HONOR_RYANMEN_SHARE = 0.0

    /**
     * 數牌 1~9 的胡牌張之中，屬於「對子系」（雙碰 + 單吊）的比例。
     *
     * 這個區分很重要：對子系聽牌需要對手**手上真的握有那張牌**，
     * 所以「這張還剩幾張沒現身」只能折抵這個比例；
     * 兩面／嵌張／邊張他握的是鄰牌，就算這張牌 4 張全現身，照樣會被胡。
     */
    private val PAIR_WAIT_SHARE_BY_RANK = doubleArrayOf(
{pair_rows}
    )

    /** 字牌只能雙碰或單吊 */
    private const val HONOR_PAIR_WAIT_SHARE = 1.0

    /** 34 種牌的基礎權重總和，用來把相對頻率換算成機率 */
    val BASE_WEIGHT_TOTAL: Double =
        (0 until Tiles.KINDS).sumOf {{ baseWeight(it) }}

    /** 第 [turn] 巡時，對手已經聽牌的機率（門清、未修正副露） */
    fun tenpaiRateByTurn(turn: Int): Double {{
        val index = (turn - 1).coerceIn(0, TENPAI_RATE_BY_TURN.size - 1)
        return maxOf(TENPAI_RATE_BY_TURN[index], MIN_TENPAI_RATE)
    }}

    /** 這種牌成為胡牌張的相對頻率 */
    fun baseWeight(tile: Int): Double =
        if (Tiles.isHonor(tile)) HONOR_DANGER else DANGER_BY_RANK[Tiles.rank(tile) - 1]

    /** 這種牌的胡牌張之中，屬於兩面聽的比例 */
    fun ryanmenShare(tile: Int): Double =
        if (Tiles.isHonor(tile)) HONOR_RYANMEN_SHARE else RYANMEN_SHARE_BY_RANK[Tiles.rank(tile) - 1]

    /** 屬於對子系（雙碰 + 單吊）的比例 */
    fun pairWaitShare(tile: Int): Double =
        if (Tiles.isHonor(tile)) HONOR_PAIR_WAIT_SHARE else PAIR_WAIT_SHARE_BY_RANK[Tiles.rank(tile) - 1]

    /** 屬於嵌張 + 邊張的比例（剩下的那一塊） */
    fun closedWaitShare(tile: Int): Double =
        (1.0 - ryanmenShare(tile) - pairWaitShare(tile)).coerceAtLeast(0.0)
}}
'''


def rows(values, per_line=6, comment=None):
    out = []
    for start in range(0, len(values), per_line):
        chunk = values[start:start + per_line]
        line = "        " + ", ".join(f"{v}" for v in chunk) + ","
        if comment:
            line += f"   // {comment(start, len(chunk))}"
        out.append(line)
    return "\n".join(out)


def main():
    stats = json.loads(STATS_PATH.read_text(encoding="utf-8"))
    ranks = [str(n) for n in range(1, 10)]

    danger = [stats["danger_by_rank"][r] for r in ranks]
    ryanmen = [stats["ryanmen_share_by_rank"][r] for r in ranks]
    pair = [stats["pair_wait_share_by_rank"][r] for r in ranks]
    tenpai = stats["tenpai_rate_by_turn"]

    source = TEMPLATE.format(
        hands=stats["meta"]["simulated_hands"],
        tenpai_hands=stats["meta"]["tenpai_hands"],
        average_waits=stats["average_waits_per_tenpai"],
        honor_danger=stats["danger_by_rank"]["字牌"],
        tenpai_rows=rows(tenpai, 6, lambda start, n: f"第 {start + 1}~{start + n} 巡"),
        danger_rows=rows(danger, 9, lambda start, n: "一 二 三 四 五 六 七 八 九"),
        ryanmen_rows=rows(ryanmen, 9, lambda start, n: "一 二 三 四 五 六 七 八 九"),
        pair_rows=rows(pair, 9, lambda start, n: "一 二 三 四 五 六 七 八 九"),
    )
    OUT_PATH.write_text(source, encoding="utf-8")
    print(f"已產生 {OUT_PATH}")
    print(f"  模擬 {stats['meta']['simulated_hands']} 手、聽牌 {stats['meta']['tenpai_hands']} 手")
    print(f"  平均聽牌張數 {stats['average_waits_per_tenpai']} 種")
    print(f"  危險度(1~9): {danger}")
    print(f"  字牌: {stats['danger_by_rank']['字牌']}")
    print(f"  兩面比例(1~9): {ryanmen}")
    print(f"  對子系比例(1~9): {pair}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
