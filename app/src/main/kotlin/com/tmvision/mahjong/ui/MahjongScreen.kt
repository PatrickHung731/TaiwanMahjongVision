package com.tmvision.mahjong.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmvision.engine.PlayStyle
import com.tmvision.engine.Seat
import com.tmvision.engine.TileRecommendation
import com.tmvision.engine.Tiles
import com.tmvision.mahjong.AdviceState
import com.tmvision.mahjong.InputTarget
import com.tmvision.mahjong.MahjongViewModel

/** 放槍機率的顏色分級——牌桌上要一眼看得出來，不能只給數字 */
private fun riskColor(risk: Double): Color = when {
    risk < 0.01 -> Color(0xFF2E7D32)      // 綠：安全
    risk < 0.03 -> Color(0xFF9E9D24)      // 黃綠
    risk < 0.06 -> Color(0xFFEF6C00)      // 橘
    else -> Color(0xFFC62828)             // 紅：危險
}

@Composable
fun MahjongScreen(viewModel: MahjongViewModel) {
    Scaffold(bottomBar = { TileKeyboard(viewModel) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            // 只算一次：這一行會跑完整的向聽數 + 放槍機率分析
            val state = viewModel.adviceState

            HeaderRow(viewModel)
            AdviceCard(state)
            if (state is AdviceState.Ready) {
                CandidateTable(state.advice.recommendations, state.advice.hasOpponentInfo)
            }
            Spacer(Modifier.height(8.dp))
            InputSection(viewModel)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ----------------------------------------------------------------------
// 頂部：風格與巡目
// ----------------------------------------------------------------------

@Composable
private fun HeaderRow(viewModel: MahjongViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayStyle.entries.forEach { style ->
            FilterChip(
                selected = viewModel.style == style,
                onClick = { viewModel.setStyle(style) },
                label = { Text(style.display, fontSize = 13.sp) },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text("第${viewModel.turn}巡", fontSize = 13.sp)
        StepperButton("−") { viewModel.adjustTurn(-1) }
        StepperButton("＋") { viewModel.adjustTurn(1) }
    }
}

// ----------------------------------------------------------------------
// 建議卡
// ----------------------------------------------------------------------

@Composable
private fun AdviceCard(state: AdviceState) {
    when (state) {
        is AdviceState.NeedTiles -> InfoCard(
            title = "還需要 ${state.expected - state.current} 張",
            body = "目前 ${state.current} / ${state.expected} 張。點下面的牌面輸入你的手牌。",
            container = MaterialTheme.colorScheme.surfaceVariant,
        )

        is AdviceState.Invalid -> InfoCard(
            title = "輸入有問題",
            body = state.message,
            container = Color(0xFFC62828),
            content = Color.White,
        )

        is AdviceState.Ready -> {
            val advice = state.advice
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = advice.overlayText(),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    advice.alternativeText()?.let { alternative ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = alternative,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    container: Color,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = content)
            Spacer(Modifier.height(4.dp))
            Text(body, fontSize = 13.sp, color = content)
        }
    }
}

// ----------------------------------------------------------------------
// 候選牌一覽
// ----------------------------------------------------------------------

@Composable
private fun CandidateTable(recommendations: List<TileRecommendation>, hasOpponentInfo: Boolean) {
    if (recommendations.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("切", fontSize = 11.sp, modifier = Modifier.width(44.dp))
                Text("向聽", fontSize = 11.sp, modifier = Modifier.width(40.dp))
                Text("進張", fontSize = 11.sp, modifier = Modifier.width(44.dp))
                Text(if (hasOpponentInfo) "放槍" else "放槍(無牌河)", fontSize = 11.sp)
            }
            HorizontalDivider(Modifier.padding(vertical = 3.dp))
            recommendations.take(6).forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        item.discardName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(44.dp),
                    )
                    Text("${item.shantenAfter}", fontSize = 14.sp, modifier = Modifier.width(40.dp))
                    Text("${item.acceptanceTiles}", fontSize = 14.sp, modifier = Modifier.width(44.dp))
                    if (hasOpponentInfo) {
                        Text(
                            text = item.riskPercent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = riskColor(item.dealInRisk),
                        )
                    } else {
                        Text("—", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 輸入區
// ----------------------------------------------------------------------

@Composable
private fun InputSection(viewModel: MahjongViewModel) {
    Text("輸入到哪裡", fontSize = 12.sp, fontWeight = FontWeight.Bold)
    // 5 個選項在手機寬度上放不下，做成可以左右滑
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
    ) {
        InputTarget.entries.forEach { target ->
            FilterChip(
                selected = viewModel.target == target,
                onClick = { viewModel.setTarget(target) },
                label = { Text(target.label, fontSize = 11.sp) },
                modifier = Modifier.padding(end = 3.dp),
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    CurrentContent(viewModel)

    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("我的副露 ${viewModel.meldedSets} 組", fontSize = 12.sp)
        StepperButton("−") { viewModel.adjustMelded(-1) }
        StepperButton("＋") { viewModel.adjustMelded(1) }
        Spacer(Modifier.width(10.dp))
        Text("花 ${viewModel.flowers}", fontSize = 12.sp)
        StepperButton("−") { viewModel.adjustFlowers(-1) }
        StepperButton("＋") { viewModel.adjustFlowers(1) }
    }

    Seat.entries.forEach { seat ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${seat.display}副露 ${viewModel.opponentMelds[seat] ?: 0} 組",
                fontSize = 12.sp,
            )
            StepperButton("−") { viewModel.adjustOpponentMelds(seat, -1) }
            StepperButton("＋") { viewModel.adjustOpponentMelds(seat, 1) }
            Spacer(Modifier.width(8.dp))
            Text("牌河 ${(viewModel.rivers[seat] ?: emptyList()).size} 張", fontSize = 11.sp)
        }
    }

    Spacer(Modifier.height(6.dp))
    Row {
        OutlinedButton(onClick = { viewModel.undo() }, modifier = Modifier.padding(end = 6.dp)) {
            Text("收回上一步", fontSize = 12.sp)
        }
        OutlinedButton(onClick = { viewModel.clearHand() }, modifier = Modifier.padding(end = 6.dp)) {
            Text("清除手牌", fontSize = 12.sp)
        }
        OutlinedButton(onClick = { viewModel.resetAll() }) {
            Text("開新局", fontSize = 12.sp)
        }
    }
}

/** 顯示目前選定位置的內容，點一下可以刪掉 */
@Composable
private fun CurrentContent(viewModel: MahjongViewModel) {
    val target = viewModel.target
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            if (target == InputTarget.HAND) {
                Text(
                    "手牌 ${viewModel.handSize} / ${viewModel.expectedRestSize}（摸牌後 ${viewModel.expectedRestSize + 1}）",
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(4.dp))
                val tiles = (0 until Tiles.KINDS).flatMap { tile -> List(viewModel.hand[tile]) { tile } }
                TileChips(tiles) { index -> viewModel.removeFromHand(tiles[index]) }
            } else {
                val river = when (target) {
                    InputTarget.MY_RIVER -> viewModel.myRiver
                    else -> viewModel.rivers[target.seat] ?: emptyList()
                }
                Text("${target.label} ${river.size} 張（依打出順序）", fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                TileChips(river) { index -> viewModel.removeFromRiver(target, index) }
            }
        }
    }
}

/** 一排可以點掉的牌 */
@Composable
private fun TileChips(tiles: List<Int>, onRemove: (Int) -> Unit) {
    if (tiles.isEmpty()) {
        Text("（空）點下面的牌面輸入", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        return
    }
    // 每列 9 張，多的往下折
    tiles.chunked(9).forEachIndexed { rowIndex, chunk ->
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            chunk.forEachIndexed { columnIndex, tile ->
                val index = rowIndex * 9 + columnIndex
                Box(
                    modifier = Modifier
                        .padding(end = 3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onRemove(index) }
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                ) {
                    Text(Tiles.displayName(tile), fontSize = 13.sp)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 牌面鍵盤
// ----------------------------------------------------------------------

@Composable
private fun TileKeyboard(viewModel: MahjongViewModel) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            SuitRow("萬", Tiles.MAN_START, 9, viewModel)
            SuitRow("筒", Tiles.PIN_START, 9, viewModel)
            SuitRow("條", Tiles.SOU_START, 9, viewModel)
            SuitRow("字", Tiles.HONOR_START, 7, viewModel)
        }
    }
}

@Composable
private fun SuitRow(label: String, start: Int, count: Int, viewModel: MahjongViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(18.dp),
        )
        for (offset in 0 until count) {
            val tile = start + offset
            TileKey(
                label = if (start == Tiles.HONOR_START) Tiles.displayName(tile) else "${offset + 1}",
                remaining = viewModel.remainingCount(tile),
                onClick = { viewModel.addTile(tile) },
                modifier = Modifier.weight(1f),
            )
        }
        // 字牌只有 7 個，補兩格讓寬度跟數牌對齊
        if (count < 9) {
            for (unused in 0 until (9 - count)) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TileKey(label: String, remaining: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = remaining > 0
    val background = if (enabled) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    }
    val foreground = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }

    Box(
        modifier = modifier
            .padding(1.dp)
            .height(46.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = foreground)
            // 這張牌還剩幾張沒出現——牌桌上最有用的資訊之一
            Text("$remaining", fontSize = 9.sp, color = foreground.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp)
    }
}
