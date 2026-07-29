#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
danger_model.py -- 放槍機率模型的 Python 對照實作

與 Kotlin `DangerEstimator` 逐行等價。用途有兩個：
1. 因為本機沒有 JDK，Kotlin 那份沒辦法直接跑，用這份確認公式算出來的**量級合理**
   （放槍機率是要給人看的數字，差一個數量級比沒有還糟）。
2. 驗證模型該有的性質：現物比較安全、絕張不可能被雙碰、中張比么九危險⋯⋯

資料來源：docs/wait_stats.json（由 generate_wait_stats.py 模擬台灣 16 張產生）

執行：
    python tools/danger_model.py
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_engine import TILE_KINDS, parse, shanten_dp, tile_name  # noqa: E402

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

STATS_PATH = Path(__file__).resolve().parent.parent / "docs" / "wait_stats.json"
STATS = json.loads(STATS_PATH.read_text(encoding="utf-8"))

TENPAI_RATE = STATS["tenpai_rate_by_turn"]
DANGER_BY_RANK = STATS["danger_by_rank"]
RYANMEN_BY_RANK = STATS["ryanmen_share_by_rank"]
PAIR_WAIT_BY_RANK = STATS["pair_wait_share_by_rank"]
AVERAGE_WAIT_KINDS = STATS["average_waits_per_tenpai"]

# 過水規則 -> 現物折扣
GENBUTSU_FACTOR = 0.35          # 台灣常見：過水只到自己下次摸牌
MELD_SPEED_UP = 0.35
FLUSH_SUIT_MULTIPLIER = 1.6
FLUSH_OTHER_MULTIPLIER = 0.7
MAX_TENPAI = 0.85
# 模擬在前幾巡的聽牌率是 0，但真實牌局早巡照樣有人聽牌（只是罕見）。
# 顯示 0.00% 會讓人誤以為絕對安全，所以給一個下限。
MIN_TENPAI = 0.01


def rank_key(tile: int) -> str:
    return "字牌" if tile >= 27 else str(tile % 9 + 1)


def base_weight(tile: int) -> float:
    return DANGER_BY_RANK[rank_key(tile)]


def ryanmen_share(tile: int) -> float:
    return RYANMEN_BY_RANK[rank_key(tile)]


def pair_wait_share(tile: int) -> float:
    """雙碰 + 單吊的比例（字牌只能這樣聽）"""
    return PAIR_WAIT_BY_RANK[rank_key(tile)]


def closed_wait_share(tile: int) -> float:
    """嵌張 + 邊張的比例（剩下的那一塊）"""
    return max(0.0, 1.0 - ryanmen_share(tile) - pair_wait_share(tile))


BASE_WEIGHT_TOTAL = sum(base_weight(t) for t in range(TILE_KINDS))


def is_suited(t):
    return t < 27


def suit_of(t):
    return t // 9 if is_suited(t) else -1


def rank_of(t):
    return t % 9 + 1 if is_suited(t) else t - 27 + 1


class Opponent:
    def __init__(self, seat, river=None, melded_sets=0, melded_tiles=None):
        self.seat = seat
        self.river = river or []
        self.melded_sets = melded_sets
        self.melded_tiles = melded_tiles or [0] * TILE_KINDS

    def has_discarded(self, tile):
        return tile in self.river

    def visible(self):
        counts = list(self.melded_tiles)
        for t in self.river:
            counts[t] = min(4, counts[t] + 1)
        return counts


class Table:
    def __init__(self, hand, opponents=None, my_river=None, turn=1):
        self.hand = hand
        self.opponents = opponents or []
        self.my_river = my_river or []
        self.turn = turn

    def seen(self):
        counts = [0] * TILE_KINDS
        for t in self.my_river:
            counts[t] = min(4, counts[t] + 1)
        for opp in self.opponents:
            v = opp.visible()
            for t in range(TILE_KINDS):
                counts[t] = min(4, counts[t] + v[t])
        return counts

    def unseen(self, tile, seen=None):
        seen = self.seen() if seen is None else seen
        return max(0, 4 - self.hand[tile] - seen[tile])


# --------------------------------------------------------------------------
def tenpai_probability(opp: Opponent, turn: int) -> float:
    index = max(0, min(len(TENPAI_RATE) - 1, turn - 1))
    base = max(TENPAI_RATE[index], MIN_TENPAI)
    return min(base * (1.0 + MELD_SPEED_UP * opp.melded_sets), MAX_TENPAI)


def ryanmen_factor(tile, opp, table, seen) -> float:
    if not is_suited(tile):
        return 0.0
    rank = rank_of(tile)
    dir_a = (rank <= 6 and not opp.has_discarded(tile + 3)
             and table.unseen(tile + 1, seen) > 0 and table.unseen(tile + 2, seen) > 0)
    dir_b = (rank >= 4 and not opp.has_discarded(tile - 3)
             and table.unseen(tile - 1, seen) > 0 and table.unseen(tile - 2, seen) > 0)
    possible = (1 if rank <= 6 else 0) + (1 if rank >= 4 else 0)
    if possible == 0:
        return 0.0
    return ((1 if dir_a else 0) + (1 if dir_b else 0)) / possible


def available(neighbour, origin, table, seen) -> bool:
    return (is_suited(neighbour) and suit_of(neighbour) == suit_of(origin)
            and table.unseen(neighbour, seen) > 0)


def closed_wait_factor(tile, table, seen) -> float:
    """嵌張／邊張：他握的是鄰牌，只看鄰牌還在不在"""
    if not is_suited(tile):
        return 0.0
    rank = rank_of(tile)
    possible = alive = 0
    if 2 <= rank <= 8:                                  # 嵌張
        possible += 1
        if available(tile - 1, tile, table, seen) and available(tile + 1, tile, table, seen):
            alive += 1
    if rank == 3:                                       # 邊張 12 聽 3
        possible += 1
        if available(tile - 2, tile, table, seen) and available(tile - 1, tile, table, seen):
            alive += 1
    if rank == 7:                                       # 邊張 89 聽 7
        possible += 1
        if available(tile + 1, tile, table, seen) and available(tile + 2, tile, table, seen):
            alive += 1
    return 0.0 if possible == 0 else alive / possible


def hold_factor(unseen) -> float:
    """雙碰／單吊：他必須握有這張牌本身"""
    return {0: 0.0, 1: 0.35, 2: 0.70, 3: 0.95}.get(unseen, 1.0)


def dominant_suit(opp: Opponent):
    if opp.melded_sets < 2:
        return None
    per_suit = [0, 0, 0]
    honors = 0
    for t in range(TILE_KINDS):
        c = opp.melded_tiles[t]
        if not c:
            continue
        if is_suited(t):
            per_suit[suit_of(t)] += c
        else:
            honors += c
    total = sum(per_suit) + honors
    if total == 0:
        return None
    best = max(range(3), key=lambda s: per_suit[s])
    if per_suit[best] > 0 and per_suit[best] + honors == total:
        return best
    return None


def flush_multiplier(tile, opp) -> float:
    suit = dominant_suit(opp)
    if suit is None:
        return 1.0
    if not is_suited(tile):
        return 1.0
    return FLUSH_SUIT_MULTIPLIER if suit_of(tile) == suit else FLUSH_OTHER_MULTIPLIER


def hit_probability(tile, opp, table, seen) -> float:
    """
    把被聽頻率依三種聽型拆開，各自套用正確的修正：
      兩面     -> 他握鄰牌，看筋牌與壁牌
      嵌張/邊張 -> 他握鄰牌，看鄰牌還在不在
      雙碰/單吊 -> 他必須握有這張牌本身，才看得出「還剩幾張」
    """
    base = base_weight(tile)
    if base <= 0:
        return 0.0
    unseen = table.unseen(tile, seen)
    weight = (base * ryanmen_share(tile) * ryanmen_factor(tile, opp, table, seen)
              + base * closed_wait_share(tile) * closed_wait_factor(tile, table, seen)
              + base * pair_wait_share(tile) * hold_factor(unseen))
    if opp.has_discarded(tile):
        weight *= GENBUTSU_FACTOR
    weight *= flush_multiplier(tile, opp)
    return max(0.0, weight) * AVERAGE_WAIT_KINDS / BASE_WEIGHT_TOTAL


def deal_in_risk_single(tile, opp, table, seen) -> float:
    return tenpai_probability(opp, table.turn) * hit_probability(tile, opp, table, seen)


def deal_in_risk(tile, table, seen=None) -> float:
    if not table.opponents:
        return 0.0
    seen = table.seen() if seen is None else seen
    safe = 1.0
    for opp in table.opponents:
        safe *= 1.0 - deal_in_risk_single(tile, opp, table, seen)
    return max(0.0, min(1.0, 1.0 - safe))


# --------------------------------------------------------------------------
# 性質驗證
# --------------------------------------------------------------------------
def check_properties() -> int:
    bad = 0

    def check(name, condition, detail=""):
        nonlocal bad
        if not condition:
            bad += 1
            print(f"  [FAIL] {name} {detail}")
        else:
            print(f"  [ok]   {name} {detail}")

    hand = parse("123m456m789m123p45p11z5s")
    # 刻意不含條子（讓條子的測試不受現物干擾），也不含東（手上已有 2 張）
    river = parse_river("19m19p234z")
    opp = Opponent("下家", river=river, melded_sets=1)
    table = Table(hand, [opp], turn=12)
    seen = table.seen()

    five_sou = 22    # 5條
    one_sou = 18     # 1條
    honor = 33       # 白

    r5 = deal_in_risk(five_sou, table, seen)
    r1 = deal_in_risk(one_sou, table, seen)
    rz = deal_in_risk(honor, table, seen)
    check("機率落在 0~1", all(0 <= r <= 1 for r in (r5, r1, rz)), f"5條={r5:.3%} 1條={r1:.3%} 白={rz:.3%}")
    check("中張比么九危險", r5 > r1, f"{r5:.3%} > {r1:.3%}")
    check("么九比字牌危險", r1 > rz, f"{r1:.3%} > {rz:.3%}")

    # 現物
    opp_genbutsu = Opponent("下家", river=river + [five_sou], melded_sets=1)
    table_g = Table(hand, [opp_genbutsu], turn=12)
    r5_genbutsu = deal_in_risk(five_sou, table_g)
    check("現物比較安全", r5_genbutsu < r5, f"{r5_genbutsu:.3%} < {r5:.3%}")

    # 筋牌：打過 2條 與 8條 -> 5條 雙筋（兩個兩面方向都被消掉）
    opp_suji = Opponent("下家", river=river + [19, 25], melded_sets=1)   # 2條(19)、8條(25)
    r5_suji = deal_in_risk(five_sou, Table(hand, [opp_suji], turn=12))
    check("雙筋比無筋安全", r5_suji < r5, f"{r5_suji:.3%} < {r5:.3%}")

    # 絕張的字牌：只能雙碰／單吊，4 張全見就真的安全
    my_river_all_white = [honor] * 2
    opp_white = Opponent("下家", river=river + [honor, honor], melded_sets=1)
    table_w = Table(hand, [opp_white], my_river=my_river_all_white, turn=12)
    check("字牌絕張才是真安全", deal_in_risk(honor, table_w) == 0.0, f"{deal_in_risk(honor, table_w):.4%}")

    # 絕張的「數牌」不安全！他可以握 4條6條 等你打 5條，跟剩幾張 5條無關
    # （3 張 5條 放在自己的牌河，所以對下家而言不是現物）
    table_five = Table(hand, [Opponent("下家", river=river, melded_sets=1)],
                       my_river=[five_sou] * 3, turn=12)
    r5_exhausted = deal_in_risk(five_sou, table_five)
    check("數牌絕張仍有風險(嵌張/兩面照樣胡)", r5_exhausted > 0.0, f"{r5_exhausted:.3%}")
    check("但絕張數牌比一般情況安全", r5_exhausted < r5, f"{r5_exhausted:.3%} < {r5:.3%}")

    # 巡目
    early = deal_in_risk(five_sou, Table(hand, [Opponent("下家", river=river[:3])], turn=4))
    late = deal_in_risk(five_sou, Table(hand, [Opponent("下家", river=river)], turn=16))
    check("巡目越晚越危險", late > early, f"第16巡 {late:.3%} > 第4巡 {early:.3%}")

    # 副露
    no_meld = deal_in_risk(five_sou, Table(hand, [Opponent("下家", river=river, melded_sets=0)], turn=12))
    melded = deal_in_risk(five_sou, Table(hand, [Opponent("下家", river=river, melded_sets=3)], turn=12))
    check("副露越多越危險", melded > no_meld, f"{melded:.3%} > {no_meld:.3%}")

    # 三家 vs 一家
    three = deal_in_risk(five_sou, Table(hand, [
        Opponent("上家", river=river), Opponent("對家", river=river), Opponent("下家", river=river),
    ], turn=12))
    one = deal_in_risk(five_sou, Table(hand, [Opponent("下家", river=river)], turn=12))
    check("三家比一家危險", three > one, f"{three:.3%} > {one:.3%}")

    return bad


def parse_river(notation: str) -> list:
    """把 MPSZ 記法展開成牌河（依序）"""
    counts = parse(notation)
    return [t for t in range(TILE_KINDS) for _ in range(counts[t])]


# --------------------------------------------------------------------------
def check_table_consistency(table: Table) -> list:
    """
    檢查場面有沒有「同一種牌超過 4 張」——
    這是 Phase 2 影像辨識最常見的失敗，寧可拒絕這一幀也不要給錯建議。
    """
    seen = table.seen()
    problems = []
    for t in range(TILE_KINDS):
        total = table.hand[t] + seen[t]
        if total > 4:
            problems.append(f"{tile_name(t)} 共出現 {total} 張")
    return problems


def demo():
    """實戰情境：第 13 巡，三家都有動作，我摸進一張中張"""
    hand = parse("123m456m789m123p45p11z5s")     # 17 張
    opponents = [
        Opponent("上家", river=parse_river("19m1z2z9p"), melded_sets=0),
        Opponent("對家", river=parse_river("1m9s5z6z2p"), melded_sets=2,
                 melded_tiles=parse("111p234p")),
        Opponent("下家", river=parse_river("9m9p3z4z7z"), melded_sets=1,
                 melded_tiles=parse("789s")),
    ]
    table = Table(hand, opponents, my_river=parse_river("1z9m"), turn=13)
    seen = table.seen()

    problems = check_table_consistency(table)
    if problems:
        print("\n[!] 場面矛盾（辨識錯誤的徵兆）：" + "、".join(problems))

    print(f"\n[實戰示範] 第 {table.turn} 巡，手牌 {sum(hand)} 張")
    print(f"  上家 牌河5張 / 對家 副露2組(筒子) / 下家 副露1組")
    print(f"\n  {'切牌':<6}{'向聽':>4}{'進張':>6}  {'放槍':>7}   說明")
    rows = []
    for tile in range(TILE_KINDS):
        if hand[tile] == 0:
            continue
        hand[tile] -= 1
        sh = shanten_dp(hand)
        acc = 0
        for t in range(TILE_KINDS):
            if hand[t] >= 4:
                continue
            hand[t] += 1
            better = shanten_dp(hand) < sh
            hand[t] -= 1
            if better:
                acc += max(0, 4 - hand[t] - seen[t] - (1 if t == tile else 0))
        hand[tile] += 1
        risk = deal_in_risk(tile, table, seen)
        rows.append((tile, sh, acc, risk))

    rows.sort(key=lambda r: (r[1], -r[2]))
    for tile, sh, acc, risk in rows:
        note = []
        for opp in opponents:
            if opp.has_discarded(tile):
                note.append(f"{opp.seat}現物")
        if table.unseen(tile, seen) == 0:
            note.append("絕張")
        print(f"  {tile_name(tile):<7}{sh:>3}{acc:>6}  {risk * 100:6.2f}%   {'、'.join(note)}")


def main():
    print("=" * 78)
    print("放槍機率模型驗證（資料來源：%s 手模擬）" % STATS["meta"]["simulated_hands"])
    print("=" * 78)
    print(f"平均聽牌張數 {AVERAGE_WAIT_KINDS} 種、權重總和 {BASE_WEIGHT_TOTAL:.2f}")
    print("\n[性質驗證]")
    bad = check_properties()
    demo()
    print("\n" + ("*** 全部通過 ***" if bad == 0 else f"*** {bad} 項不符預期 ***"))
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
