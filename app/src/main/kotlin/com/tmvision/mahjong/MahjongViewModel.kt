package com.tmvision.mahjong

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.tmvision.engine.DiscardAdvisor
import com.tmvision.engine.Flower
import com.tmvision.engine.HandState
import com.tmvision.engine.OpponentState
import com.tmvision.engine.PlayStyle
import com.tmvision.engine.Seat
import com.tmvision.engine.ShantenCalculator
import com.tmvision.engine.TableAdvice
import com.tmvision.engine.TableState
import com.tmvision.engine.Tiles

/** 現在點牌面是要輸入到哪裡 */
enum class InputTarget(val label: String) {
    HAND("我的手牌"),
    MY_RIVER("我打過的"),
    LEFT_RIVER("上家牌河"),
    ACROSS_RIVER("對家牌河"),
    RIGHT_RIVER("下家牌河");

    /** 對應的座位；手牌與自己的牌河沒有座位 */
    val seat: Seat?
        get() = when (this) {
            LEFT_RIVER -> Seat.LEFT
            ACROSS_RIVER -> Seat.ACROSS
            RIGHT_RIVER -> Seat.RIGHT
            else -> null
        }
}

/** 畫面要顯示什麼 */
sealed interface AdviceState {
    /** 手牌還沒輸入完 */
    data class NeedTiles(val current: Int, val expected: Int) : AdviceState

    /** 輸入有矛盾（例如同一種牌超過 4 張） */
    data class Invalid(val message: String) : AdviceState

    /** 可以給建議了 */
    data class Ready(val advice: TableAdvice) : AdviceState
}

/**
 * 手動輸入版的狀態管理。
 *
 * 相機辨識（Phase 2）做好之後，只要把辨識結果餵進 [setHand] 與各家牌河即可，
 * 這一層與畫面都不用改——這也是先做手動版的原因：
 * 辨識錯的時候本來就需要手動修正介面，不會白做。
 */
class MahjongViewModel : ViewModel() {

    private val advisor = DiscardAdvisor()

    /** 自己的暗牌，長度 34 的張數表 */
    var hand by mutableStateOf(List(Tiles.KINDS) { 0 })
        private set

    /** 自己的副露組數（吃碰槓各算 1 組） */
    var meldedSets by mutableIntStateOf(0)
        private set

    /** 自己補了幾張花 */
    var flowers by mutableIntStateOf(0)
        private set

    /** 自己打出去的牌 */
    var myRiver by mutableStateOf(emptyList<Int>())
        private set

    /** 三家的牌河 */
    var rivers by mutableStateOf(Seat.entries.associateWith { emptyList<Int>() })
        private set

    /** 三家的副露組數 */
    var opponentMelds by mutableStateOf(Seat.entries.associateWith { 0 })
        private set

    var target by mutableStateOf(InputTarget.HAND)
        private set

    var style by mutableStateOf(PlayStyle.BALANCED)
        private set

    /** 巡目的手動修正量（預設由牌河長度自動推算） */
    private var turnOffset by mutableIntStateOf(0)

    /** 目前手牌張數 */
    val handSize: Int get() = hand.sum()

    /** 待摸狀態應有的張數（未副露 = 16） */
    val expectedRestSize: Int get() = (ShantenCalculator.SETS_FOR_WIN - meldedSets) * 3 + 1

    /**
     * 巡目：用最長的那條牌河推算，可以手動微調。
     * 巡目會直接影響「對手已經聽牌」的機率，所以值得顯示出來讓人核對。
     */
    val turn: Int
        get() {
            val longest = maxOf(myRiver.size, rivers.values.maxOfOrNull { it.size } ?: 0)
            return (longest + 1 + turnOffset).coerceIn(1, 30)
        }

    /** 這種牌在場上已經出現幾張（手牌 + 所有牌河） */
    fun usedCount(tile: Int): Int =
        hand[tile] + myRiver.count { it == tile } + rivers.values.sumOf { river -> river.count { it == tile } }

    /** 這種牌還剩幾張沒出現——鍵盤上直接顯示，牌桌上很好用 */
    fun remainingCount(tile: Int): Int = (Tiles.MAX_PER_KIND - usedCount(tile)).coerceAtLeast(0)

    // ------------------------------------------------------------------
    // 輸入
    // ------------------------------------------------------------------

    /** 點一下牌面：加進目前選定的位置 */
    fun addTile(tile: Int) {
        if (remainingCount(tile) <= 0) return                 // 4 張都出現了，一定是點錯
        when (target) {
            InputTarget.HAND -> {
                if (handSize >= expectedRestSize + 1) return  // 已經 17 張，不能再加
                hand = hand.toMutableList().also { it[tile]++ }
            }
            InputTarget.MY_RIVER -> myRiver = myRiver + tile
            else -> {
                val seat = target.seat ?: return
                rivers = rivers.toMutableMap().also { it[seat] = (it[seat] ?: emptyList()) + tile }
            }
        }
    }

    /** 從手牌移除一張（點手牌上的那張牌） */
    fun removeFromHand(tile: Int) {
        if (hand[tile] <= 0) return
        hand = hand.toMutableList().also { it[tile]-- }
    }

    /** 移除牌河中指定位置的牌（點錯了可以直接改） */
    fun removeFromRiver(target: InputTarget, index: Int) {
        when (target) {
            InputTarget.MY_RIVER ->
                myRiver = myRiver.filterIndexed { i, _ -> i != index }
            else -> {
                val seat = target.seat ?: return
                val river = rivers[seat] ?: return
                rivers = rivers.toMutableMap().also { it[seat] = river.filterIndexed { i, _ -> i != index } }
            }
        }
    }

    /** 收回最後一次輸入 */
    fun undo() {
        when (target) {
            InputTarget.HAND -> {
                val last = hand.indexOfLast { it > 0 }
                if (last >= 0) removeFromHand(last)
            }
            InputTarget.MY_RIVER -> if (myRiver.isNotEmpty()) myRiver = myRiver.dropLast(1)
            else -> {
                val seat = target.seat ?: return
                val river = rivers[seat] ?: return
                if (river.isNotEmpty()) rivers = rivers.toMutableMap().also { it[seat] = river.dropLast(1) }
            }
        }
    }

    // 不能叫 setTarget / setStyle：`var target by mutableStateOf(...)` 已經產生了
    // JVM 上的 setTarget()，同名函式會撞簽章（Platform declaration clash）。
    fun selectTarget(value: InputTarget) {
        target = value
    }

    fun selectStyle(value: PlayStyle) {
        style = value
    }

    fun adjustMelded(delta: Int) {
        val next = (meldedSets + delta).coerceIn(0, ShantenCalculator.SETS_FOR_WIN)
        // 副露會改變手牌應有的張數，多出來的牌要先清掉，不然狀態會卡住
        if (next > meldedSets) {
            var overflow = handSize - ((ShantenCalculator.SETS_FOR_WIN - next) * 3 + 2)
            if (overflow > 0) {
                val working = hand.toMutableList()
                for (tile in working.indices.reversed()) {
                    while (overflow > 0 && working[tile] > 0) {
                        working[tile]--
                        overflow--
                    }
                }
                hand = working
            }
        }
        meldedSets = next
    }

    fun adjustFlowers(delta: Int) {
        flowers = (flowers + delta).coerceIn(0, Flower.COUNT)
    }

    fun adjustOpponentMelds(seat: Seat, delta: Int) {
        val current = opponentMelds[seat] ?: 0
        val next = (current + delta).coerceIn(0, ShantenCalculator.SETS_FOR_WIN)
        opponentMelds = opponentMelds.toMutableMap().also { it[seat] = next }
    }

    fun adjustTurn(delta: Int) {
        turnOffset += delta
    }

    /** 清掉手牌，牌河留著（下一局才需要全部重置） */
    fun clearHand() {
        hand = List(Tiles.KINDS) { 0 }
        flowers = 0
        meldedSets = 0
    }

    /** 開新局 */
    fun resetAll() {
        hand = List(Tiles.KINDS) { 0 }
        meldedSets = 0
        flowers = 0
        myRiver = emptyList()
        rivers = Seat.entries.associateWith { emptyList() }
        opponentMelds = Seat.entries.associateWith { 0 }
        turnOffset = 0
        target = InputTarget.HAND
    }

    /** 給 Phase 2 的相機辨識用：直接覆寫手牌 */
    fun setHand(counts: IntArray) {
        hand = counts.toList()
    }

    // ------------------------------------------------------------------
    // 建議
    // ------------------------------------------------------------------

    val adviceState: AdviceState
        get() {
            val size = handSize
            val rest = expectedRestSize
            if (size < rest) return AdviceState.NeedTiles(size, rest)
            if (size > rest + 1) return AdviceState.Invalid("手牌 $size 張太多了（最多 ${rest + 1} 張）")

            return try {
                val table = TableState(
                    hand = HandState(hand.toIntArray(), meldedSets, flowers),
                    opponents = Seat.entries.map { seat ->
                        OpponentState(
                            seat = seat,
                            river = rivers[seat] ?: emptyList(),
                            meldedSets = opponentMelds[seat] ?: 0,
                        )
                    },
                    myRiver = myRiver,
                    turn = turn,
                )
                val problems = table.inconsistencies()
                if (problems.isNotEmpty()) {
                    AdviceState.Invalid(problems.first())
                } else {
                    AdviceState.Ready(advisor.advise(table, style))
                }
            } catch (error: IllegalArgumentException) {
                AdviceState.Invalid(error.message ?: "輸入有誤")
            }
        }
}
