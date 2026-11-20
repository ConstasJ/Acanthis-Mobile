package io.acanthis.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MIN_ZOOM_SCALE = 1f
private const val DOUBLE_TAP_ZOOM_SCALE = 2.5f
private const val MAX_ZOOM_SCALE = 4f

@Composable
fun IllustrationViewerDialog(
    imageUrl: String,
    contentDescription: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember(imageUrl) { mutableFloatStateOf(MIN_ZOOM_SCALE) }
        var offset by remember(imageUrl) { mutableStateOf(Offset.Zero) }
        var containerSize by remember { mutableStateOf(IntSize.Zero) }
        var imageState by remember(imageUrl) {
            mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
        }

        fun clampOffset(candidate: Offset, targetScale: Float): Offset {
            if (targetScale <= MIN_ZOOM_SCALE || containerSize.width <= 0 || containerSize.height <= 0) {
                return Offset.Zero
            }
            val maxX = (containerSize.width * (targetScale - 1f)) / 2f
            val maxY = (containerSize.height * (targetScale - 1f)) / 2f
            return Offset(
                x = candidate.x.coerceIn(-maxX, maxX),
                y = candidate.y.coerceIn(-maxY, maxY),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { containerSize = it }
                .pointerInput(imageUrl) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (scale > MIN_ZOOM_SCALE + 0.05f) {
                                scale = MIN_ZOOM_SCALE
                                offset = Offset.Zero
                            } else {
                                val nextScale = DOUBLE_TAP_ZOOM_SCALE
                                val centerX = containerSize.width / 2f
                                val centerY = containerSize.height / 2f
                                val targetOffset = Offset(
                                    x = (centerX - tapOffset.x) * (nextScale - 1f),
                                    y = (centerY - tapOffset.y) * (nextScale - 1f),
                                )
                                scale = nextScale
                                offset = clampOffset(targetOffset, nextScale)
                            }
                        },
                    )
                }
                .pointerInput(imageUrl) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
                        val nextOffset = if (nextScale <= MIN_ZOOM_SCALE) {
                            Offset.Zero
                        } else {
                            clampOffset(offset + pan, nextScale)
                        }
                        scale = nextScale
                        offset = nextOffset
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                onState = { imageState = it },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )

            when (imageState) {
                is AsyncImagePainter.State.Loading -> {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }

                is AsyncImagePainter.State.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BrokenImage,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = "插图加载失败",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }

                else -> Unit
            }

            // Top-right close button
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭插图查看器",
                        tint = Color.White,
                    )
                }
            }

            // Zoom badge & optional caption at bottom
            val showZoomBadge = scale > MIN_ZOOM_SCALE + 0.05f
            val caption = contentDescription.takeIf { it.isNotBlank() && it != "章节插图" }
            if (showZoomBadge || caption != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    val label = buildString {
                        if (caption != null) {
                            append(caption)
                        }
                        if (showZoomBadge) {
                            if (isNotEmpty()) append(" · ")
                            append(formatZoomScale(scale))
                            append("x")
                        }
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun formatZoomScale(scale: Float): String {
    val tenths = (scale * 10f).roundToInt()
    val whole = tenths / 10
    val frac = abs(tenths % 10)
    return "$whole.$frac"
}
