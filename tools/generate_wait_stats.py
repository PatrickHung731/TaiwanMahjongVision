#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate_wait_stats.py -- 產生「台灣 16 張」專屬的危險度統計表

為什麼要自己跑
--------------
放槍機率的核心是：**如果對手聽牌，他聽這張的機率有多高？**
網路上找得到的筋牌／危險度統計全部是日本麻將（4 面子 + 1 眼、13 張）的數據，
台灣是 5 面子 + 1 眼、16 張，牌型結構完全不同，直接套會算錯。

所以這支程式用蒙地卡羅模擬台灣 16 張的實際打牌過程，統計出三張表：

  1. tenpai_rate_by_turn[]  各巡目的聽牌率  -> P(對手聽牌 | 第 n 巡)
  2. wait_frequency[34]     各種牌成為胡牌張的頻率 -> 基礎危險度
  3. wait_type_share        聽型分布(兩面/嵌張/邊張/雙碰/單吊) -> 筋牌能折抵多少危險

模擬方式
--------
洗一副 136 張的牌（花牌另計不影響組合），發 16 張，之後每巡摸一張、
用「向聽數最小」的貪心法打一張，直到聽牌或摸完 18 巡。
打法是簡化版（平手時優先打字牌／么九，不比較進張數），
因此結果偏保守，但牌型結構的相對關係是可信的。

執行：
    python tools/generate_wait_stats.py --hands 2000
    python tools/generate_wait_stats.py --hands 200 --quick    # 先看看跑不跑得動
"""

import argparse
import json
import random
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_engine import (  # noqa: E402
    TILE_KINDS, SETS_FOR_WIN, can_win, can_start_run, parse, shanten_dp, tile_name, to_mpsz,
)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

MAX_TURNS = 18          # 136 張 - 四家 64 張手牌 = 72 張可摸，每家約 18 巡
HAND_SIZE = 16

WAIT_TYPES = ["兩面", "嵌張", "邊張", "雙碰", "單吊"]


# --------------------------------------------------------------------------
# 打牌模擬
# --------------------------------------------------------------------------
def keep_value(tile: int) -> int:
    """留牌價值：越小越先打掉（字牌 < 么九 < 二八 < 中張）"""
    if tile >= 27:
        return 0
    rank = tile % 9 + 1
    if rank in (1, 9):
        return 1
    if rank in (2, 8):
        return 2
    return 3


def build_wall(rng: random.Random) -> list:
    wall = [t for t in range(TILE_KINDS) for _ in range(4)]
    rng.shuffle(wall)
    return wall


def choose_discard(hand: list, rng: random.Random) -> int:
    """貪心打牌：向聽數最小者優先，平手時打價值最低的牌"""
    best_tile, best_key = None, None
    for tile in range(TILE_KINDS):
        if hand[tile] == 0:
            continue
        hand[tile] -= 1
        sh = shanten_dp(hand)
        hand[tile] += 1
        key = (sh, keep_value(tile), rng.random())
        if best_key is None or key < best_key:
            best_tile, best_key = tile, key
    return best_tile


def simulate_hand(rng: random.Random):
    """
    模擬一手牌打到聽牌。

    回傳 (聽牌手牌, 聽牌巡目) 或 None（摸完 18 巡還沒聽牌／中途自摸）。
    """
    wall = build_wall(rng)
    hand = [0] * TILE_KINDS
    for _ in range(HAND_SIZE):
        hand[wall.pop()] += 1

    for turn in range(1, MAX_TURNS + 1):
        if not wall:
            return None, turn
        hand[wall.pop()] += 1                       # 摸進 -> 17 張
        if can_win(hand):
            return None, turn                       # 自摸，不列入聽牌統計
        hand[choose_discard(hand, rng)] -= 1        # 打出 -> 16 張
        if shanten_dp(hand) == 0:
            return hand[:], turn
    return None, MAX_TURNS


# --------------------------------------------------------------------------
# 聽型判定
# --------------------------------------------------------------------------
def winning_tiles(hand: list) -> list:
    """16 張聽牌手牌聽哪些牌"""
    out = []
    for t in range(TILE_KINDS):
        if hand[t] >= 4:
            continue
        hand[t] += 1
        if can_win(hand):
            out.append(t)
        hand[t] -= 1
    return out


def _decompose(counts: list, needed: int, groups: list) -> bool:
    """把 counts 全部拆成 needed 個面子（回傳第一組解）"""
    if needed == 0:
        return not any(counts)
    i = next((k for k, c in enumerate(counts) if c > 0), None)
    if i is None:
        return False
    if counts[i] >= 3:
        counts[i] -= 3
        groups.append(("刻", i))
        if _decompose(counts, needed - 1, groups):
            counts[i] += 3
            return True
        groups.pop()
        counts[i] += 3
    if can_start_run(i) and counts[i + 1] > 0 and counts[i + 2] > 0:
        counts[i] -= 1; counts[i + 1] -= 1; counts[i + 2] -= 1
        groups.append(("順", i))
        if _decompose(counts, needed - 1, groups):
            counts[i] += 1; counts[i + 1] += 1; counts[i + 2] += 1
            return True
        groups.pop()
        counts[i] += 1; counts[i + 1] += 1; counts[i + 2] += 1
    return False


def wait_type(hand: list, win_tile: int) -> str:
    """
    判斷「聽 win_tile」是哪一種聽型。

    作法：把 hand + win_tile 拆成 5 面子 + 1 眼，找出 win_tile 所在的那一組，
    看它在組裡的位置就知道是兩面、嵌張、邊張、雙碰還是單吊。
    一手牌可能有多種拆法，這裡取找到的第一組（統計上足夠）。
    """
    full = hand[:]
    full[win_tile] += 1
    for pair in range(TILE_KINDS):
        if full[pair] < 2:
            continue
        full[pair] -= 2
        groups = []
        ok = _decompose(full[:], SETS_FOR_WIN, groups)
        full[pair] += 2
        if not ok:
            continue

        if pair == win_tile:
            # 胡的牌就是眼：手上原本只有 1 張 -> 單吊
            return "單吊"
        for kind, start in groups:
            if kind == "刻" and start == win_tile:
                return "雙碰"
            if kind == "順" and start <= win_tile <= start + 2:
                offset = win_tile - start
                if offset == 1:
                    return "嵌張"
                rank = start % 9 + 1
                if offset == 0:
                    # 搭子是 (start+1, start+2)，同時聽 start 與 start+3；
                    # 只有搭子是 89（start 為 7）時另一端不存在，才是邊張
                    return "邊張" if rank == 7 else "兩面"
                # offset == 2，搭子是 (start, start+1)，同時聽 start-1 與 start+2；
                # 只有搭子是 12（start 為 1）時另一端不存在，才是邊張
                return "邊張" if rank == 1 else "兩面"
        return "單吊"
    return "單吊"


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------
def run(hands: int, seed: int):
    rng = random.Random(seed)
    reached_tenpai = [0] * (MAX_TURNS + 2)     # 第 n 巡「首次聽牌」的手數
    attempts = 0
    wait_counter = Counter()
    type_counter = Counter()
    type_by_rank = {}                          # rank -> Counter(聽型)
    tenpai_hands = 0
    total_waits = 0
    started = time.time()

    for index in range(hands):
        attempts += 1
        hand, turn = simulate_hand(rng)
        if hand is None:
            continue
        tenpai_hands += 1
        reached_tenpai[turn] += 1

        waits = winning_tiles(hand)
        total_waits += len(waits)
        for tile in waits:
            wait_counter[tile] += 1
            kind = wait_type(hand, tile)
            type_counter[kind] += 1
            rank_key = "字牌" if tile >= 27 else str(tile % 9 + 1)
            type_by_rank.setdefault(rank_key, Counter())[kind] += 1

        if (index + 1) % 200 == 0:
            elapsed = time.time() - started
            print(f"  ... {index + 1}/{hands} 手，聽牌 {tenpai_hands} 手，{elapsed:.0f}s")

    # ---- 1. 聽牌率 vs 巡目（累積）----
    print("\n[1] 各巡目累積聽牌率（P(對手已聽牌 | 第 n 巡)）")
    cumulative = 0
    tenpai_rate = []
    for turn in range(1, MAX_TURNS + 1):
        cumulative += reached_tenpai[turn]
        rate = cumulative / attempts
        tenpai_rate.append(round(rate, 4))
        bar = "#" * int(rate * 50)
        print(f"  第 {turn:>2} 巡  {rate * 100:5.1f}%  {bar}")

    # ---- 2. 各牌被聽頻率 ----
    print("\n[2] 各種牌成為胡牌張的相對頻率（基礎危險度）")
    by_rank = Counter()
    for tile, count in wait_counter.items():
        by_rank["字牌" if tile >= 27 else str(tile % 9 + 1)] += count
    honor_kinds, suit_kinds = 7, 3          # 字牌 7 種、每個點數在 3 個花色各 1 種
    normalized = {}
    for key, count in by_rank.items():
        kinds = honor_kinds if key == "字牌" else suit_kinds
        normalized[key] = count / kinds
    peak = max(normalized.values())
    danger_by_rank = {}
    for key in [str(n) for n in range(1, 10)] + ["字牌"]:
        score = normalized.get(key, 0) / peak
        danger_by_rank[key] = round(score, 3)
        print(f"  {key:<4} {score * 100:5.1f}%  {'#' * int(score * 50)}")

    # ---- 3. 聽型分布 ----
    print("\n[3] 聽型分布（決定筋牌能折抵多少危險度）")
    for kind in WAIT_TYPES:
        share = type_counter[kind] / max(1, total_waits)
        print(f"  {kind}  {share * 100:5.1f}%  {'#' * int(share * 50)}")

    print("\n[4] 各點數的聽型分布")
    print("    兩面佔比 = 筋牌最多能折抵的比例")
    print("    對子系(雙碰+單吊)佔比 = 「這張牌剩幾張」能影響的比例")
    print(f"  {'點數':<6}" + "".join(f"{k:>8}" for k in WAIT_TYPES))
    ryanmen_share = {}
    pair_share = {}
    for key in [str(n) for n in range(1, 10)] + ["字牌"]:
        counter = type_by_rank.get(key, Counter())
        total = sum(counter.values()) or 1
        ryanmen_share[key] = round(counter["兩面"] / total, 3)
        pair_share[key] = round((counter["雙碰"] + counter["單吊"]) / total, 3)
        row = "".join(f"{counter[k] / total * 100:7.1f}%" for k in WAIT_TYPES)
        print(f"  {key:<6}" + row)

    stats = {
        "meta": {
            "rule": "台灣16張 (5面子+1眼)",
            "simulated_hands": attempts,
            "tenpai_hands": tenpai_hands,
            "seed": seed,
            "note": "貪心打牌模擬，未含吃碰槓與他家干擾；作為危險度先驗使用",
        },
        "tenpai_rate_by_turn": tenpai_rate,
        "danger_by_rank": danger_by_rank,
        "wait_type_share": {k: round(type_counter[k] / max(1, total_waits), 3) for k in WAIT_TYPES},
        "ryanmen_share_by_rank": ryanmen_share,
        # 雙碰 + 單吊：這些聽型需要對手手上真的握有那張牌，
        # 所以「這張還剩幾張沒現身」只能折抵這個比例。
        # 兩面／嵌張／邊張他握的是鄰牌，就算這張絕張了照樣會被胡。
        "pair_wait_share_by_rank": pair_share,
        "wait_type_by_rank": {
            key: {k: counter[k] for k in WAIT_TYPES}
            for key, counter in type_by_rank.items()
        },
        "average_waits_per_tenpai": round(total_waits / max(1, tenpai_hands), 2),
    }
    out_path = Path(__file__).resolve().parent.parent / "docs" / "wait_stats.json"
    out_path.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n平均每個聽牌手牌聽 {stats['average_waits_per_tenpai']} 種牌")
    print(f"統計結果已寫入 {out_path}")
    print(f"總耗時 {time.time() - started:.0f}s")
    return stats


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--hands", type=int, default=2000, help="模擬手數")
    parser.add_argument("--seed", type=int, default=20260728)
    parser.add_argument("--quick", action="store_true", help="只跑 200 手")
    args = parser.parse_args()
    hands = 200 if args.quick else args.hands

    print("=" * 78)
    print(f"台灣 16 張危險度統計（模擬 {hands} 手）")
    print("=" * 78)
    run(hands, args.seed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
