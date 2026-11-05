// commonMain/kotlin/com/varuna/rustify/ui/screens/VerticalScrollbarWithTooltip.kt
package com.varuna.rustify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun VerticalScrollbarWithTooltip(
    lazyListState: androidx.compose.foundation.lazy.LazyListState,
    itemsCount: Int,
    getDateForItem: (Int) -> String,
    onDragStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (itemsCount <= 0) return

    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var scrollbarHeight by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isDragging) {
        onDragStateChanged(isDragging)
    }

    val firstVisibleIndex by remember { derivedStateOf { lazyListState.firstVisibleItemIndex } }
    val scrollFraction = if (itemsCount > 1) firstVisibleIndex.toFloat() / (itemsCount - 1) else 0f

    val dragFraction = if (scrollbarHeight > 0) (dragOffset / scrollbarHeight).coerceIn(0f, 1f) else 0f
    val activeIndex = if (isDragging) {
        (dragFraction * (itemsCount - 1)).toInt().coerceIn(0, itemsCount - 1)
    } else {
        firstVisibleIndex.coerceIn(0, itemsCount - 1)
    }
    val tooltipText = remember(activeIndex, isDragging) { getDateForItem(activeIndex) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 16.dp)
            .onGloballyPositioned { coordinates ->
                scrollbarHeight = coordinates.size.height.toFloat()
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val newDragOffset = offset.y.coerceIn(0f, scrollbarHeight)
                        dragOffset = newDragOffset
                        val currentFraction = if (scrollbarHeight > 0) newDragOffset / scrollbarHeight else 0f
                        val targetIndex = (currentFraction * (itemsCount - 1)).toInt().coerceIn(0, itemsCount - 1)
                        scrollJob?.cancel()
                        scrollJob = coroutineScope.launch {
                            lazyListState.scrollToItem(targetIndex)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newDragOffset = (dragOffset + dragAmount.y).coerceIn(0f, scrollbarHeight)
                        dragOffset = newDragOffset
                        val currentFraction = if (scrollbarHeight > 0) newDragOffset / scrollbarHeight else 0f
                        val targetIndex = (currentFraction * (itemsCount - 1)).toInt().coerceIn(0, itemsCount - 1)
                        scrollJob?.cancel()
                        scrollJob = coroutineScope.launch {
                            lazyListState.scrollToItem(targetIndex)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .fillMaxHeight()
                .width(2.dp)
                .background(Color.Gray.copy(alpha = 0.2f))
        )

        val handleSize = 40.dp
        val density = LocalDensity.current
        val handleSizePx = with(density) { handleSize.toPx() }
        
        val handleOffset = if (!isDragging) {
            val maxOffset = scrollbarHeight - handleSizePx
            (scrollFraction * maxOffset).coerceAtLeast(0f)
        } else {
            (dragOffset - handleSizePx / 2).coerceIn(0f, scrollbarHeight - handleSizePx)
        }

        val handleOffsetDp = with(density) { handleOffset.toDp() }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 2.dp)
                .offset(y = handleOffsetDp)
                .size(width = 6.dp, height = handleSize)
                .background(
                    color = if (isDragging) Color(0xFF1DB954) else Color.Gray.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(3.dp)
                )
        )

        if (isDragging && tooltipText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = (-16).dp,
                        y = handleOffsetDp + (handleSize / 2) - 16.dp
                    )
                    .background(Color(0xFF2E2E2E), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = tooltipText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
