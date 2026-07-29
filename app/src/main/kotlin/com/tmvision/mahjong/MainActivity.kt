package com.tmvision.mahjong

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tmvision.mahjong.ui.MahjongScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MahjongTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val viewModel: MahjongViewModel = viewModel()
                    MahjongScreen(viewModel)
                }
            }
        }
    }
}

// 麻將桌的綠色系。刻意不用動態取色（Material You），
// 因為危險度的紅／綠是有意義的資訊，被系統主題染色會失真。
private val TableGreen = Color(0xFF1B5E20)
private val TableGreenLight = Color(0xFF4C8C4A)
private val TileCream = Color(0xFFFFF8E1)

private val LightColors = lightColorScheme(
    primary = TableGreen,
    onPrimary = Color.White,
    primaryContainer = TableGreenLight,
    onPrimaryContainer = Color.White,
    surfaceVariant = TileCream,
)

private val DarkColors = darkColorScheme(
    primary = TableGreenLight,
    onPrimary = Color.Black,
    primaryContainer = TableGreen,
    onPrimaryContainer = Color.White,
)

@Composable
fun MahjongTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
