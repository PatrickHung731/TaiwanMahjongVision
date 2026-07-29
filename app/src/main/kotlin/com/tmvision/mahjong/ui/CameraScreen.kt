package com.tmvision.mahjong.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tmvision.engine.Tiles
import com.tmvision.mahjong.MahjongViewModel
import com.tmvision.mahjong.vision.Guide
import com.tmvision.mahjong.vision.TileMatch
import com.tmvision.mahjong.vision.TileMemory
import com.tmvision.mahjong.vision.featureOf
import com.tmvision.mahjong.vision.rotated
import com.tmvision.mahjong.vision.sliceStrip
import java.io.File

/** 相機畫面的兩種模式 */
private enum class CameraMode(val label: String) {
    CALIBRATE("教學模式"),
    RECOGNIZE("辨識手牌"),
}

/** 教學模式的一個步驟：排一列牌拍一張，一次就標好整排 */
private data class CalibrationStep(val title: String, val tiles: List<Int>, val hint: String)

private val CALIBRATION_STEPS = listOf(
    CalibrationStep("萬子", (0..8).toList(), "把 1萬 到 9萬 由左到右依序排好，對進框裡"),
    CalibrationStep("筒子", (9..17).toList(), "把 1筒 到 9筒 由左到右依序排好，對進框裡"),
    CalibrationStep("條子", (18..26).toList(), "把 1條 到 9條 由左到右依序排好，對進框裡"),
    CalibrationStep("字牌", (27..33).toList(), "把 東 南 西 北 中 發 白 由左到右排好，對進框裡"),
)

/** 直式 4:3 相機的畫面寬高比 */
private const val FRAME_ASPECT = 0.75f

@Composable
fun CameraScreen(viewModel: MahjongViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val memory = remember {
        TileMemory(File(context.filesDir, "tile_memory.csv")).apply { load() }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) requestPermission.launch(Manifest.permission.CAMERA)
    }

    var mode by remember { mutableStateOf(if (memory.isReady) CameraMode.RECOGNIZE else CameraMode.CALIBRATE) }
    var stepIndex by remember { mutableIntStateOf(0) }
    var handTileCount by remember { mutableIntStateOf(viewModel.expectedRestSize) }
    var slices by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var matches by remember { mutableStateOf<List<TileMatch?>>(emptyList()) }
    var selectedSlice by remember { mutableIntStateOf(-1) }
    var memoryVersion by remember { mutableIntStateOf(0) }

    val sliceCount = if (mode == CameraMode.CALIBRATE) {
        CALIBRATION_STEPS[stepIndex].tiles.size
    } else {
        handTileCount
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- 頂端：模式與關閉 ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraMode.entries.forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = {
                        mode = item
                        selectedSlice = -1
                    },
                    label = { Text(item.label, fontSize = 12.sp) },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("關閉", fontSize = 12.sp) }
        }

        // ---- 相機預覽 ----
        if (!hasPermission) {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("需要相機權限", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("這個 App 完全離線，畫面不會離開手機。", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) {
                        Text("允許使用相機")
                    }
                }
            }
        } else {
            CameraPreview(
                sliceCount = sliceCount,
                onSlices = { newSlices ->
                    slices = newSlices
                    matches = if (mode == CameraMode.RECOGNIZE) {
                        newSlices.map { memory.match(featureOf(it)) }
                    } else {
                        emptyList()
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(FRAME_ASPECT),
            )
        }

        // ---- 模式各自的操作區 ----
        when (mode) {
            CameraMode.CALIBRATE -> CalibratePanel(
                stepIndex = stepIndex,
                slices = slices,
                memory = memory,
                memoryVersion = memoryVersion,
                onCaptured = {
                    memoryVersion++
                    if (stepIndex < CALIBRATION_STEPS.lastIndex) {
                        stepIndex++
                    } else {
                        mode = CameraMode.RECOGNIZE
                    }
                },
                onStepChange = { stepIndex = it },
            )

            CameraMode.RECOGNIZE -> RecognizePanel(
                viewModel = viewModel,
                memory = memory,
                slices = slices,
                matches = matches,
                handTileCount = handTileCount,
                onHandTileCountChange = { handTileCount = it },
                selectedSlice = selectedSlice,
                onSelectSlice = { selectedSlice = it },
                onCorrected = { index, tile ->
                    slices.getOrNull(index)?.let { memory.remember(tile, featureOf(it)) }
                    memory.save()
                    memoryVersion++
                    matches = matches.toMutableList().also {
                        if (index in it.indices) it[index] = TileMatch(tile, 0f, Float.MAX_VALUE)
                    }
                    selectedSlice = -1
                },
                onClose = onClose,
            )
        }
    }
}

// ----------------------------------------------------------------------
// 相機
// ----------------------------------------------------------------------

@Composable
private fun CameraPreview(
    sliceCount: Int,
    onSlices: (List<Bitmap>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 用 holder 讓 analyzer 讀到最新的值，而不是被建立當下的值綁死
    val sliceCountHolder = remember { intArrayOf(sliceCount) }
    sliceCountHolder[0] = sliceCount
    val callbackHolder = remember { arrayOfNulls<(List<Bitmap>) -> Unit>(1) }
    callbackHolder[0] = onSlices

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val resolution = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(resolution)
                        .build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    // 每秒只真的處理一張影格，其餘直接丟掉——這就是低功耗的關鍵
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(viewContext)) { image ->
                        handleFrame(image, sliceCountHolder[0]) { result ->
                            callbackHolder[0]?.invoke(result)
                        }
                    }

                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                }, ContextCompat.getMainExecutor(viewContext))
                previewView
            },
        )

        // 對準框：跟實際裁切用同一組計算，看到的框就是切的範圍
        Canvas(Modifier.fillMaxSize()) {
            val guideHeight = Guide.heightFraction(sliceCount, FRAME_ASPECT) * size.height
            val guideTop = Guide.topFraction(sliceCount, FRAME_ASPECT) * size.height
            val guideLeft = Guide.LEFT * size.width
            val guideWidth = (Guide.RIGHT - Guide.LEFT) * size.width

            drawRect(
                color = Color(0xFF4CAF50),
                topLeft = Offset(guideLeft, guideTop),
                size = Size(guideWidth, guideHeight),
                style = Stroke(width = 4f),
            )
            // 切割線，方便確認每一格有沒有對到一張牌
            for (index in 1 until sliceCount) {
                val x = guideLeft + guideWidth * index / sliceCount
                drawLine(
                    color = Color(0x804CAF50),
                    start = Offset(x, guideTop),
                    end = Offset(x, guideTop + guideHeight),
                    strokeWidth = 2f,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }
}

private var lastFrameAt = 0L

/** 1 FPS 閘門：一秒內的其他影格直接丟棄，避免手機過熱降頻 */
private fun handleFrame(image: ImageProxy, sliceCount: Int, onSlices: (List<Bitmap>) -> Unit) {
    try {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < 1000L) return
        lastFrameAt = now
        val upright = image.toBitmap().rotated(image.imageInfo.rotationDegrees)
        onSlices(sliceStrip(upright, sliceCount))
    } catch (error: Throwable) {
        // 單一影格失敗不該讓整個相機掛掉
    } finally {
        image.close()
    }
}

// ----------------------------------------------------------------------
// 教學模式
// ----------------------------------------------------------------------

@Composable
private fun CalibratePanel(
    stepIndex: Int,
    slices: List<Bitmap>,
    memory: TileMemory,
    memoryVersion: Int,
    onCaptured: () -> Unit,
    onStepChange: (Int) -> Unit,
) {
    val step = CALIBRATION_STEPS[stepIndex]

    Column(Modifier.padding(8.dp)) {
        Text("教學模式（${stepIndex + 1} / ${CALIBRATION_STEPS.size}）", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(step.hint, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))

        Row(Modifier.horizontalScroll(rememberScrollState())) {
            CALIBRATION_STEPS.forEachIndexed { index, item ->
                val done = memoryVersion >= 0 && item.tiles.all { memory.samplesFor(it) > 0 }
                FilterChip(
                    selected = index == stepIndex,
                    onClick = { onStepChange(index) },
                    label = { Text(if (done) "${item.title} ✓" else item.title, fontSize = 12.sp) },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        SliceStrip(slices = slices, labels = step.tiles.map { Tiles.displayName(it) })

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                slices.forEachIndexed { index, bitmap ->
                    step.tiles.getOrNull(index)?.let { tile -> memory.remember(tile, featureOf(bitmap)) }
                }
                memory.save()
                onCaptured()
            },
            enabled = slices.size == step.tiles.size,
        ) {
            Text("拍下這一排（${step.tiles.size} 張）")
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "目前記住 ${memory.knownKinds} / ${Tiles.KINDS} 種牌、共 ${memory.sampleCount} 個樣本",
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { memory.clear(); onCaptured() }) {
            Text("清空重學", fontSize = 12.sp)
        }
    }
}

// ----------------------------------------------------------------------
// 辨識模式
// ----------------------------------------------------------------------

@Composable
private fun RecognizePanel(
    viewModel: MahjongViewModel,
    memory: TileMemory,
    slices: List<Bitmap>,
    matches: List<TileMatch?>,
    handTileCount: Int,
    onHandTileCountChange: (Int) -> Unit,
    selectedSlice: Int,
    onSelectSlice: (Int) -> Unit,
    onCorrected: (Int, Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.padding(8.dp)) {
        if (!memory.isReady) {
            Text(
                "還沒學完（${memory.knownKinds} / ${Tiles.KINDS} 種）。先去教學模式排牌拍四次。",
                fontSize = 12.sp,
                color = Color(0xFFC62828),
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("手牌張數 $handTileCount", fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            listOf(viewModel.expectedRestSize, viewModel.expectedRestSize + 1).forEach { count ->
                FilterChip(
                    selected = handTileCount == count,
                    onClick = { onHandTileCountChange(count) },
                    label = { Text("$count", fontSize = 12.sp) },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        SliceStrip(
            slices = slices,
            labels = matches.map { match ->
                when {
                    match == null -> "?"
                    match.isConfident -> match.name
                    else -> "${match.name}?"
                }
            },
            selectedIndex = selectedSlice,
            onSelect = onSelectSlice,
        )

        Spacer(Modifier.height(6.dp))
        if (selectedSlice >= 0) {
            Text("第 ${selectedSlice + 1} 格認錯了？點正確的牌，它就會記住", fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            TilePicker { tile -> onCorrected(selectedSlice, tile) }
        } else {
            Text("認錯的話點那一格，再選正確的牌（改一次它就學會了）", fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val counts = IntArray(Tiles.KINDS)
                matches.forEach { match ->
                    if (match != null && counts[match.tile] < Tiles.MAX_PER_KIND) counts[match.tile]++
                }
                viewModel.setHand(counts)
                onClose()
            },
            enabled = matches.isNotEmpty() && matches.all { it != null },
        ) {
            Text("套用到手牌並回到建議畫面")
        }
    }
}

// ----------------------------------------------------------------------
// 共用元件
// ----------------------------------------------------------------------

/** 切出來的每一格縮圖 + 標籤。看得到縮圖才知道是「框沒對準」還是「認錯牌」 */
@Composable
private fun SliceStrip(
    slices: List<Bitmap>,
    labels: List<String>,
    selectedIndex: Int = -1,
    onSelect: ((Int) -> Unit)? = null,
) {
    if (slices.isEmpty()) {
        Text("把手牌對進上面的框裡…", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        slices.forEachIndexed { index, bitmap ->
            val selected = index == selectedIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(end = 3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Color(0xFFC62828) else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .clickable(enabled = onSelect != null) { onSelect?.invoke(index) }
                    .padding(2.dp),
            ) {
                androidx.compose.foundation.Image(
                    painter = BitmapPainter(bitmap.asImageBitmap()),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 26.dp, height = 36.dp),
                )
                Text(labels.getOrElse(index) { "" }, fontSize = 10.sp)
            }
        }
    }
}

/** 修正用的牌面選單 */
@Composable
private fun TilePicker(onPick: (Int) -> Unit) {
    Column {
        listOf(0 until 9, 9 until 18, 18 until 27, 27 until 34).forEach { range ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                range.forEach { tile ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(1.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPick(tile) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(Tiles.displayName(tile), fontSize = 11.sp)
                    }
                }
                repeat(9 - range.count()) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
