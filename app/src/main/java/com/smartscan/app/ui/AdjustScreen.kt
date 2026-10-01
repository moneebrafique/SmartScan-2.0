package com.smartscan.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.smartscan.app.data.Adjust
import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.FilterType
import com.smartscan.app.processing.ImageProcessor
import com.smartscan.app.processing.ImageUtils
import com.smartscan.app.processing.ScanActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Live-preview screen: filter presets + manual brightness/contrast/sharpness/shadow/colour controls. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
@Composable
fun AdjustScreen(docId: String, pageId: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val page = remember(docId, pageId) { DocumentRepository.get(docId)?.pages?.firstOrNull { it.id == pageId } }
    if (page == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val pageCount = remember(docId) { DocumentRepository.get(docId)?.pages?.size ?: 1 }

    var filter by remember { mutableStateOf(page.filter) }
    var adjust by remember { mutableStateOf(page.adjust) }
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var before by remember { mutableStateOf<ImageBitmap?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var computing by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val renderer = remember { Dispatchers.Default.limitedParallelism(1) }

    LaunchedEffect(page.id) {
        val b = withContext(Dispatchers.Default) {
            val src = ImageUtils.load(DocumentRepository.file(docId, page.originalFile), 1400)
            val warped = ImageProcessor.warpBitmap(src, page.corners)
            src.recycle()
            warped
        }
        before = withContext(renderer) {
            ImageProcessor.render(b, FilterType.ORIGINAL, Adjust(), page.rotation).asImageBitmap()
        }
        base = b
    }

    LaunchedEffect(base) {
        val b = base ?: return@LaunchedEffect
        snapshotFlow { filter to adjust }.collectLatest { (f, a) ->
            delay(30)
            computing = true
            val bmp = withContext(renderer) { ImageProcessor.render(b, f, a, page.rotation) }
            preview = bmp.asImageBitmap()
            computing = false
        }
    }

    fun save(allPages: Boolean) {
        if (saving) return
        saving = true
        val f = filter
        val a = adjust
        scope.launch {
            ScanActions.defaultFilter = f
            if (allPages) ScanActions.applyStyleToAll(docId, f, a)
            else ScanActions.editPage(docId, pageId) { it.copy(filter = f, adjust = a) }
            saving = false
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enhance") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                },
                actions = {
                    TextButton(onClick = { adjust = Adjust() }, enabled = adjust != Adjust()) { Text("Reset") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Preview (press and hold to see the original)
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF121212))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            comparing = true
                            tryAwaitRelease()
                            comparing = false
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                val shown = if (comparing) before else preview
                if (shown == null) {
                    CircularProgressIndicator()
                } else {
                    Image(
                        bitmap = shown,
                        contentDescription = "Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                }
                if (computing) {
                    LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
                }
                Text(
                    if (comparing) "Original" else "Hold to compare",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                if (saving) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            // Presets
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(FilterType.entries) { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }

            // Manual controls
            Column(Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState())) {
                if (filter == FilterType.BW) {
                    AdjustSlider("Text darkness", adjust.textWeight) { adjust = adjust.copy(textWeight = it) }
                    AdjustSlider("Sharpness", adjust.sharpness) { adjust = adjust.copy(sharpness = it) }
                } else {
                    AdjustSlider("Brightness", adjust.brightness) { adjust = adjust.copy(brightness = it) }
                    AdjustSlider("Contrast", adjust.contrast) { adjust = adjust.copy(contrast = it) }
                    AdjustSlider("Sharpness", adjust.sharpness) { adjust = adjust.copy(sharpness = it) }
                    AdjustSlider("Shadow removal", adjust.shadows) { adjust = adjust.copy(shadows = it) }
                    if (filter != FilterType.GRAYSCALE) {
                        AdjustSlider("Color", adjust.color) { adjust = adjust.copy(color = it) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (pageCount > 1) {
                    OutlinedButton(onClick = { save(allPages = true) }, enabled = !saving, modifier = Modifier.weight(1f)) {
                        Text("Apply to all")
                    }
                }
                Button(onClick = { save(allPages = false) }, enabled = !saving, modifier = Modifier.weight(1f)) {
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                if (value > 0) "+$value" else "$value",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = -100f..100f,
            modifier = Modifier.height(32.dp),
        )
    }
}
