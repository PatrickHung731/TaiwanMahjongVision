#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_engine.py -- TaiwanMahjongEngine (Phase 1) 演算法交叉驗證工具

為什麼需要這支程式
------------------
台灣 16 張（5 面子 + 1 眼）的向聽數演算法網路上幾乎沒有現成可比對的實作，
一旦公式寫錯，UI 會很有自信地建議你切錯牌。所以這裡用「三套互相獨立的方法」
互相驗證，Kotlin 單元測試的期望值全部由本檔產生：

  1. shanten_dp()     最大重疊 DP。與 Kotlin ShantenCalculator 逐行等價。
                      列舉所有合法胡牌型 W，取 max( sum_i min(W_i, hand_i) )，
                      向聽數 = (胡牌張數 - 最大重疊) - 1。
  2. shanten_block()  傳統面子/搭子分解公式（日麻常見寫法推廣到 5 面子）。
                      結構上與 (1) 完全不同，用來大量交叉比對。
  3. brute_shanten()  暴力窮舉真值。只依賴「胡牌型定義」與「換幾張牌能聽牌」，
                      不含任何公式。速度慢，只對低向聽手牌驗證。

執行（不需要 JDK）：
    python tools/verify_engine.py
    python tools/verify_engine.py --quick     # 略過較慢的暴力窮舉

牌索引 (34 陣列)
----------------
  0~8   一萬~九萬   (m)      9~17  一筒~九筒 (p)
  18~26 一條~九條   (s)      27~33 東南西北中發白 (z)
"""

import random
import sys
import time
from functools import lru_cache

if hasattr(sys.stdout, "reconfigure"):          # Windows 主控台預設 cp950，會炸中文
    sys.stdout.reconfigure(encoding="utf-8")

TILE_KINDS = 34
SETS_FOR_WIN = 5          # 台灣 16 張：5 個面子 + 1 個眼
NEG = -9999

SUIT_BASE = {"m": 0, "p": 9, "s": 18, "z": 27}
CN_SUIT = ["萬", "筒", "條"]
CN_HONOR = ["東", "南", "西", "北", "中", "發", "白"]


# --------------------------------------------------------------------------
# 牌面表示
# --------------------------------------------------------------------------
def parse(text: str) -> list:
    """MPSZ 記法 -> 34 陣列。例：'123m456p789s11z'"""
    counts = [0] * TILE_KINDS
    buf = []
    for ch in text:
        if ch.isdigit():
            buf.append(int(ch))
        elif ch in SUIT_BASE:
            base = SUIT_BASE[ch]
            for d in buf:
                assert 1 <= d <= (7 if ch == "z" else 9), f"非法牌面 {d}{ch}"
                counts[base + d - 1] += 1
            buf = []
        elif ch in " -_,":
            continue
        else:
            raise ValueError(f"無法解析的字元: {ch!r}")
    assert not buf, "記法結尾缺少 m/p/s/z"
    assert all(c <= 4 for c in counts), "同一種牌超過 4 張"
    return counts


def tile_name(i: int) -> str:
    if i >= 27:
        return CN_HONOR[i - 27]
    return f"{i % 9 + 1}{CN_SUIT[i // 9]}"


def to_mpsz(counts: list) -> str:
    out = []
    for s, ch in enumerate("mps"):
        digits = "".join(str(i % 9 + 1) * counts[i] for i in range(s * 9, s * 9 + 9))
        if digits:
            out.append(digits + ch)
    digits = "".join(str(i - 27 + 1) * counts[i] for i in range(27, 34))
    if digits:
        out.append(digits + "z")
    return "".join(out)


def hand_str(counts: list) -> str:
    return " ".join(tile_name(i) for i in range(TILE_KINDS) for _ in range(counts[i]))


def can_start_run(i: int) -> bool:
    """i 是否可以當順子起點（字牌不行、8/9 起頭不行，因此也不會跨花色）"""
    return i < 27 and i % 9 <= 6


# --------------------------------------------------------------------------
# 方法 1：最大重疊 DP —— 與 Kotlin ShantenCalculator 等價
# --------------------------------------------------------------------------
# memo 用「陣列 + 世代戳記」，與 Kotlin ShantenCalculator 完全一致（也快了將近一倍）
_MEMO_SIZE = (TILE_KINDS + 1) * (SETS_FOR_WIN + 1) * 2 * 5 * 5
_MEMO_VALUE = [0] * _MEMO_SIZE
_MEMO_STAMP = [0] * _MEMO_SIZE
_STAMP = 0


def _search(counts, i, melds_left, pair_used, a, b):
    if i == TILE_KINDS:
        return 0 if (melds_left == 0 and pair_used and a == 0 and b == 0) else NEG

    key = ((((i * (SETS_FOR_WIN + 1) + melds_left) * 2 + pair_used) * 5 + a) * 5) + b
    if _MEMO_STAMP[key] == _STAMP:
        return _MEMO_VALUE[key]

    best = NEG
    have = counts[i]
    max_s = min(melds_left, 4) if can_start_run(i) else 0
    for t in (0, 1):                                   # 在 i 放 0/1 組刻子
        if t > melds_left:
            continue
        for s in range(0, max_s + 1):                  # 由 i 起始的順子數
            if t + s > melds_left:
                break
            for p in (0, 1):                           # 在 i 放眼
                if p and pair_used:
                    continue
                w = 3 * t + 2 * p + s + a + b          # 目標牌型 W 在 i 上的張數
                if w > 4:
                    continue
                sub = _search(counts, i + 1, melds_left - t - s, pair_used | p, b, s)
                if sub < 0:
                    continue
                val = sub + (w if w < have else have)
                if val > best:
                    best = val

    _MEMO_STAMP[key] = _STAMP
    _MEMO_VALUE[key] = best
    return best


def max_overlap(counts: list, needed_melds: int) -> int:
    """
    列舉所有合法胡牌型 W（needed_melds 個面子 + 1 個眼、每種牌 <= 4 張），
    回傳 max( sum_i min(W_i, hand_i) )。

    狀態 (i, melds_left, pair_used, a, b)：
      a = 由 i-2 起始、仍覆蓋到 i 的順子數
      b = 由 i-1 起始、仍覆蓋到 i 的順子數
    """
    global _STAMP
    _STAMP += 1
    return _search(counts, 0, needed_melds, 0, 0, 0)


def shanten_dp(counts: list, melded_sets: int = 0) -> int:
    """向聽數。-1 = 已胡牌、0 = 聽牌。"""
    needed = SETS_FOR_WIN - melded_sets
    return (needed * 3 + 2) - max_overlap(counts, needed) - 1


# --------------------------------------------------------------------------
# 方法 2：傳統面子/搭子分解公式（結構上與方法 1 完全不同）
# --------------------------------------------------------------------------
def shanten_block(counts: list, melded_sets: int = 0) -> int:
    """
    shanten = 2*needed - 2*(面子數) - (搭子數)，其中：
      * 面子 + 搭子 <= needed + 1（最多 6 個區塊）
      * 若區塊已滿且沒有任何對子可當眼，+1
    """
    needed = SETS_FOR_WIN - melded_sets
    max_blocks = needed + 1
    work = list(counts)
    best = [99]

    def record(melds, partials, has_pair):
        s = 2 * needed - 2 * melds - partials
        if melds + partials == max_blocks and not has_pair:
            s += 1
        if s < best[0]:
            best[0] = s

    def dfs(i, melds, partials, has_pair):
        if i > 33 or melds + partials >= max_blocks:
            record(melds, partials, has_pair)
            return
        # 分支 A：不在 i 開任何區塊（剩下的 i 都是孤張）
        dfs(i + 1, melds, partials, has_pair)
        # 分支 B：刻子
        if work[i] >= 3:
            work[i] -= 3
            dfs(i, melds + 1, partials, has_pair)
            work[i] += 3
        # 分支 C：順子
        if can_start_run(i) and work[i] and work[i + 1] and work[i + 2]:
            work[i] -= 1; work[i + 1] -= 1; work[i + 2] -= 1
            dfs(i, melds + 1, partials, has_pair)
            work[i] += 1; work[i + 1] += 1; work[i + 2] += 1
        # 分支 D：對子（可當眼、也可長成刻子）
        if work[i] >= 2:
            work[i] -= 2
            dfs(i, melds, partials + 1, True)
            work[i] += 2
        # 分支 E：兩面/邊張搭子
        if i < 27 and i % 9 <= 7 and work[i] and work[i + 1]:
            work[i] -= 1; work[i + 1] -= 1
            dfs(i, melds, partials + 1, has_pair)
            work[i] += 1; work[i + 1] += 1
        # 分支 F：嵌張搭子
        if can_start_run(i) and work[i] and work[i + 2]:
            work[i] -= 1; work[i + 2] -= 1
            dfs(i, melds, partials + 1, has_pair)
            work[i] += 1; work[i + 2] += 1

    dfs(0, 0, 0, False)
    return best[0]


# --------------------------------------------------------------------------
# 方法 3：暴力窮舉真值（不含任何公式）
# --------------------------------------------------------------------------
@lru_cache(maxsize=None)
def _all_melds(tup: tuple, is_suit: bool) -> bool:
    """該花色的牌能否完全拆成面子（不留餘牌）"""
    if not any(tup):
        return True
    i = next(k for k, c in enumerate(tup) if c > 0)
    lst = list(tup)
    if lst[i] >= 3:
        lst[i] -= 3
        if _all_melds(tuple(lst), is_suit):
            return True
        lst[i] += 3
    if is_suit and i + 2 < 9 and lst[i + 1] > 0 and lst[i + 2] > 0:
        lst[i] -= 1; lst[i + 1] -= 1; lst[i + 2] -= 1
        if _all_melds(tuple(lst), is_suit):
            return True
    return False


def can_win(counts: list) -> bool:
    """counts 是否為完整胡牌型（n 個面子 + 1 個眼）。張數必須是 3n+2。"""
    if sum(counts) % 3 != 2:
        return False
    for p in range(TILE_KINDS):
        if counts[p] < 2:
            continue
        counts[p] -= 2
        ok = (_all_melds(tuple(counts[0:9]), True)
              and _all_melds(tuple(counts[9:18]), True)
              and _all_melds(tuple(counts[18:27]), True)
              and _all_melds(tuple(counts[27:34]), False))
        counts[p] += 2
        if ok:
            return True
    return False


def _relevant_tiles(counts: list):
    """能夠影響牌型的牌：與手上任何一張牌同種或距離 <= 2（其餘牌摸到必為孤張）"""
    hit = set()
    for i, c in enumerate(counts):
        if not c:
            continue
        hit.add(i)
        if i < 27:
            for d in (-2, -1, 1, 2):
                j = i + d
                if j // 9 == i // 9 and 0 <= j < 27:
                    hit.add(j)
    return sorted(hit)


def is_tenpai(counts: list) -> bool:
    """3n+1 張手牌是否聽牌"""
    for t in _relevant_tiles(counts):
        if counts[t] >= 4:
            continue
        counts[t] += 1
        ok = can_win(counts)
        counts[t] -= 1
        if ok:
            return True
    return False


def _successors(counts: list):
    """所有「打 1 張 + 摸 1 張」之後的手牌（張數不變）"""
    draws = _relevant_tiles(counts)
    for x in range(TILE_KINDS):
        if counts[x] == 0:
            continue
        counts[x] -= 1
        for y in draws:
            if y == x or counts[y] >= 4:
                continue
            counts[y] += 1
            yield list(counts)
            counts[y] -= 1
        counts[x] += 1


def _reachable(counts: list, depth: int) -> bool:
    """depth 次換牌內能否到達聽牌"""
    if depth == 0:
        return is_tenpai(counts)
    for nxt in _successors(counts):
        if _reachable(nxt, depth - 1):
            return True
    return False


def brute_shanten(counts: list, max_depth: int = 2):
    """
    暴力真值：只用「胡牌型定義」+「換牌次數」。
    回傳向聽數；若超過 max_depth 回傳 None（代表 > max_depth）。
    """
    work = list(counts)
    if sum(work) % 3 == 2:                 # 3n+2（摸牌後）：先看是否已胡，否則丟一張再算
        if can_win(work):
            return -1
        best = None
        for x in range(TILE_KINDS):
            if work[x] == 0:
                continue
            work[x] -= 1
            v = brute_shanten(work, max_depth)
            work[x] += 1
            if v is not None and (best is None or v < best):
                best = v
                if best == 0:
                    break
        return best
    if is_tenpai(work):
        return 0
    for d in range(1, max_depth + 1):
        if _reachable(work, d):
            return d
    return None


# --------------------------------------------------------------------------
# 進張 / 切牌分析（與 Kotlin TaiwanMahjongEngine 等價）
# --------------------------------------------------------------------------
def acceptance(counts: list, melded_sets: int = 0, seen=None, just_discarded: int = -1):
    """
    3n+1 張手牌的有效進張 [(tile, 剩餘張數), ...]

    just_discarded: 剛打出去的那張牌也已經進了牌河，剩餘張數要再扣 1
                    （與 Kotlin TaiwanMahjongEngine.acceptanceOf 一致）
    """
    seen = seen or [0] * TILE_KINDS
    base = shanten_dp(counts, melded_sets)
    out = []
    for t in range(TILE_KINDS):
        if counts[t] >= 4:
            continue
        counts[t] += 1
        improved = shanten_dp(counts, melded_sets) < base
        counts[t] -= 1
        if improved:
            in_river = seen[t] + (1 if t == just_discarded else 0)
            remaining = max(0, 4 - counts[t] - in_river)
            if remaining > 0:
                out.append((t, remaining))
    return out


def best_discards(counts: list, melded_sets: int = 0, seen=None):
    """3n+2 張手牌 -> 依 (向聽, 進張總數) 排序的切牌建議"""
    seen = seen or [0] * TILE_KINDS
    options = []
    for d in range(TILE_KINDS):
        if counts[d] == 0:
            continue
        counts[d] -= 1
        sh = shanten_dp(counts, melded_sets)
        acc = acceptance(counts, melded_sets, seen, just_discarded=d)
        counts[d] += 1
        options.append((d, sh, sum(n for _, n in acc), acc))
    options.sort(key=lambda o: (o[1], -o[2], o[0]))
    return options


# --------------------------------------------------------------------------
# 測資產生
# --------------------------------------------------------------------------
def random_hand(size: int, rng: random.Random) -> list:
    counts = [0] * TILE_KINDS
    n = 0
    while n < size:
        t = rng.randrange(TILE_KINDS)
        if counts[t] < 4:
            counts[t] += 1
            n += 1
    return counts


def random_winning_hand(rng: random.Random, needed_melds: int = SETS_FOR_WIN) -> list:
    """隨機產生一副合法胡牌型（needed_melds 個面子 + 1 個眼）"""
    while True:
        counts = [0] * TILE_KINDS
        ok = True
        for _ in range(needed_melds):
            for _try in range(60):
                if rng.random() < 0.45:                     # 刻子
                    t = rng.randrange(TILE_KINDS)
                    if counts[t] <= 1:
                        counts[t] += 3
                        break
                else:                                       # 順子
                    t = rng.randrange(27)
                    if t % 9 <= 6 and all(counts[t + k] <= 3 for k in range(3)):
                        for k in range(3):
                            counts[t + k] += 1
                        break
            else:
                ok = False
                break
        if not ok:
            continue
        for _try in range(60):
            t = rng.randrange(TILE_KINDS)
            if counts[t] <= 2:
                counts[t] += 2
                break
        else:
            continue
        if sum(counts) == needed_melds * 3 + 2 and max(counts) <= 4:
            return counts


def mutate(counts: list, k: int, rng: random.Random) -> list:
    """把 k 張牌換成別的牌（用來製造 0~k 向聽的測資）"""
    work = list(counts)
    for _ in range(k):
        src = [i for i in range(TILE_KINDS) if work[i] > 0]
        x = rng.choice(src)
        work[x] -= 1
        for _try in range(60):
            y = rng.randrange(TILE_KINDS)
            if work[y] < 4:
                work[y] += 1
                break
        else:
            work[x] += 1
    return work


def near_tenpai_hand(rng: random.Random, k: int) -> list:
    """從胡牌型倒推：拿掉一張變成 16 張聽牌，再隨機換掉 k 張"""
    win = random_winning_hand(rng)
    src = [i for i in range(TILE_KINDS) if win[i] > 0]
    win[rng.choice(src)] -= 1
    return mutate(win, k, rng)


# --------------------------------------------------------------------------
# 驗證流程
# --------------------------------------------------------------------------
def check_two_algorithms(rounds: int, seed: int) -> int:
    """方法 1 (DP) vs 方法 2 (面子分解公式)：大量隨機牌"""
    rng = random.Random(seed)
    bad = 0
    dist = {}
    t0 = time.time()
    cases = []
    for _ in range(rounds):
        cases.append((random_hand(16, rng), 0))                       # 純亂牌
        cases.append((near_tenpai_hand(rng, rng.randint(0, 3)), 0))   # 接近聽牌
        cases.append((random_hand(17, rng), 0))                       # 摸牌後
        m = rng.randint(1, 4)
        cases.append((random_hand((SETS_FOR_WIN - m) * 3 + 1, rng), m))  # 副露
    for hand, melded in cases:
        a = shanten_dp(hand, melded)
        b = shanten_block(hand, melded)
        dist[a] = dist.get(a, 0) + 1
        if a != b:
            bad += 1
            if bad <= 10:
                print(f"  [不一致] DP={a} 分解公式={b} 副露={melded} {to_mpsz(hand)}")
    print(f"  [1] DP vs 面子分解公式：{len(cases)} 手，不一致 {bad} 手 "
          f"({time.time() - t0:.1f}s)")
    print(f"      向聽分佈 {dict(sorted(dist.items()))}")
    return bad


def check_win_consistency(rounds: int, seed: int) -> int:
    """shanten == -1 必須等價於「可拆成 5 面子 + 1 眼」"""
    rng = random.Random(seed)
    bad = 0
    for _ in range(rounds):
        hand = random_hand(17, rng)
        if (shanten_dp(hand) == -1) != can_win(hand):
            bad += 1
            print(f"  [不一致] 胡牌判定 {to_mpsz(hand)}")
    for _ in range(rounds):
        hand = random_winning_hand(rng)
        if shanten_dp(hand) != -1 or not can_win(hand):
            bad += 1
            print(f"  [不一致] 應為胡牌 {to_mpsz(hand)}")
    print(f"  [2] 胡牌判定 (shanten==-1 vs 牌型拆解)：{rounds * 2} 手，不一致 {bad} 手")
    return bad


def check_brute_force(seed: int, samples: int = 60) -> int:
    """方法 3：對低向聽手牌做真正的暴力窮舉"""
    rng = random.Random(seed)
    bad = 0
    tested = {}
    t0 = time.time()
    tries = 0
    while sum(tested.values()) < samples and tries < samples * 12:
        tries += 1
        hand = near_tenpai_hand(rng, rng.randint(0, 2))
        dp = shanten_dp(hand)
        if dp > 2 or tested.get(dp, 0) >= samples // 3:
            continue
        truth = brute_shanten(hand, max_depth=2)
        tested[dp] = tested.get(dp, 0) + 1
        if dp != truth:
            bad += 1
            print(f"  [不一致] DP={dp} 暴力真值={truth} {to_mpsz(hand)}")
    # 17 張（摸牌後）狀態
    for _ in range(20):
        hand = mutate(random_winning_hand(rng), rng.randint(0, 1), rng)
        dp = shanten_dp(hand)
        if dp > 1:
            continue
        truth = brute_shanten(hand, max_depth=1)
        if dp != truth:
            bad += 1
            print(f"  [不一致] 17張 DP={dp} 暴力真值={truth} {to_mpsz(hand)}")
    print(f"  [3] 暴力窮舉真值：{dict(sorted(tested.items()))} 共 {sum(tested.values())} 手"
          f"（另含 17 張），不一致 {bad} 手 ({time.time() - t0:.1f}s)")
    return bad


FIXTURES = [
    # (說明, MPSZ, 副露面子數)
    ("完整胡牌 5面子+1眼",        "123m456m789m123p456p11z", 0),
    ("單吊聽牌",                  "123m456m789m123p456p1z",  0),
    ("兩面聽牌",                  "123m456m789m123p45p11z",  0),
    ("嵌張聽牌",                  "123m456m789m123p46p11z",  0),
    ("邊張聽牌",                  "123m456m789m123p12p11z",  0),
    ("雙碰聽牌",                  "123m456m789m123p55p11z",  0),
    ("五面子無眼(單吊型)",        "123m456m789m123p456p9s",  0),
    ("一向聽 (兩面+孤張)",        "123m456m789m123p45p12z",  0),
    ("二向聽",                    "123m456m789m12p45p12z9s", 0),
    ("八對子 (同花色)",           "1122334455667788m",       0),
    ("七對+對子 (無順子可用)",    "1199m1199p1199s1122z",    0),
    ("全字牌爛牌",                "1234567z1234567z11z",     0),
    ("副露2組 + 10張",            "123m456m78p11z",          2),
    ("副露3組 + 7張",             "123m45m11z",              3),
    ("副露4組 + 4張",             "45m11z",                  4),
    ("副露1組 + 13張(待摸)",      "123m456m789m45p11z",      1),
    ("副露1組 + 14張(待切)",      "123m456m789m45p11z9s",    1),
]


def print_fixtures():
    print("\n[Kotlin 單元測試期望值]  (兩套演算法一致才輸出)")
    for desc, text, melded in FIXTURES:
        hand = parse(text)
        dp = shanten_dp(hand, melded)
        blk = shanten_block(hand, melded)
        size = sum(hand)
        rest = (SETS_FOR_WIN - melded) * 3 + 1
        state = "待摸(3n+1)" if size == rest else ("待切(3n+2)" if size == rest + 1 else f"張數異常{size}")
        mark = "" if dp == blk else f"  <<< 兩法不一致 block={blk}"
        line = f"  {desc:<22}{text:<26} 副露={melded} {size:>2}張 {state:<11} shanten={dp}{mark}"
        print(line)
        if dp == 0 and size == rest:
            waits = acceptance(hand, melded)
            print(f"       聽 {', '.join(f'{tile_name(t)}({n}張)' for t, n in waits)}")


def demo_discard():
    text = "123m456m789m123p45p11z9s"
    hand = parse(text)
    print(f"\n[切牌建議示範] 17 張 {text} -> {hand_str(hand)}")
    seen = [0] * TILE_KINDS
    seen[9 + 2] = 2            # 假設海底已見 2 張 3筒
    for tile, sh, total, acc in best_discards(hand, 0, seen)[:5]:
        acc_txt = ", ".join(f"{tile_name(t)}x{n}" for t, n in acc) or "無"
        print(f"  切 {tile_name(tile):<4}-> {sh} 向聽，進張 {total:>2} 張  [{acc_txt}]")


def main():
    quick = "--quick" in sys.argv
    print("=" * 78)
    print("TaiwanMahjongEngine Phase 1 演算法驗證（台灣 16 張：5 面子 + 1 眼）")
    print("=" * 78)
    bad = 0
    bad += check_two_algorithms(rounds=400 if not quick else 60, seed=20260728)
    bad += check_win_consistency(rounds=800 if not quick else 100, seed=555)
    if not quick:
        bad += check_brute_force(seed=4242)
    print_fixtures()
    demo_discard()
    print("\n" + ("*** 全部通過 ***" if bad == 0 else f"*** 發現 {bad} 筆不一致 ***"))
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
