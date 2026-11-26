package com.luminagallery.drive.ui.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Apple Photos / HyperOS "content-aware return": while the Viewer pages through photos, the grid
 * underneath silently follows so the close morph always lands on the photo you ended on.
 *
 *  - Already fully visible: nothing moves (your scroll position is preserved exactly).
 *  - Partially clipped at the top / bottom edge: nudged just enough to be fully visible with a
 *    small breathing margin, so the rest of the grid stays where you left it.
 *  - Off-screen: centred in the viewport so the photo flies home into the middle of the screen
 *    instead of the very top edge or underneath a toolbar.
 *
 * @param edgeMarginPx breathing room kept between the tile and the viewport edge when nudging.
 */
suspend fun LazyGridState.revealItemForReturnMorph(
    index: Int,
    edgeMarginPx: Int = 0
) {
    if (index < 0) return
    var info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    var centre = false
    if (info == null) {
        scrollToItem(index)
        info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        centre = true
    }
    val delta = returnMorphScrollDelta(
        itemStart = info.offset.y,
        itemSize = info.size.height,
        visibleStart = layoutInfo.viewportStartOffset + layoutInfo.beforeContentPadding,
        visibleEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
        edgeMarginPx = edgeMarginPx,
        centre = centre
    )
    if (delta != 0f) scrollBy(delta)
}

/**
 * Same as [revealItemForReturnMorph] for grids whose photos are the *last* items (after a header,
 * filters or chips). Uses the item key when it is laid out, otherwise derives the absolute index
 * from the trailing list position.
 */
suspend fun LazyGridState.revealTrailingItemForReturnMorph(
    key: Any,
    indexInList: Int,
    listSize: Int,
    edgeMarginPx: Int = 0
) {
    if (indexInList < 0 || listSize <= 0) return
    val visibleIndex = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.index
    val total = layoutInfo.totalItemsCount
    val index = visibleIndex ?: (total - listSize + indexInList)
    if (index !in 0 until total) return
    revealItemForReturnMorph(index, edgeMarginPx)
}

/**
 * Drop-in effect for photo grids: whenever the Viewer reports a new active photo, keeps the grid
 * underneath aligned so the close morph lands exactly on the photo you ended on.
 */
@Composable
fun ReturnMorphScrollEffect(
    gridState: LazyGridState,
    lastViewedItemId: String?,
    itemIds: List<String>,
    onConsumed: () -> Unit
) {
    val marginPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val latestOnConsumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(lastViewedItemId, itemIds) {
        val targetId = lastViewedItemId ?: return@LaunchedEffect
        val indexInList = itemIds.indexOf(targetId)
        if (indexInList >= 0) {
            gridState.revealTrailingItemForReturnMorph(targetId, indexInList, itemIds.size, marginPx)
            latestOnConsumed()
        }
    }
}

/** Pure scroll-delta maths for [revealItemForReturnMorph] (unit tested). Positive = scroll content up. */
internal fun returnMorphScrollDelta(
    itemStart: Int,
    itemSize: Int,
    visibleStart: Int,
    visibleEnd: Int,
    edgeMarginPx: Int,
    centre: Boolean
): Float {
    val itemEnd = itemStart + itemSize
    val viewport = visibleEnd - visibleStart
    if (viewport <= 0) return 0f
    if (centre || itemSize + edgeMarginPx * 2 >= viewport) {
        val itemCentre = itemStart + itemSize / 2f
        val viewportCentre = visibleStart + viewport / 2f
        return itemCentre - viewportCentre
    }
    return when {
        itemStart < visibleStart -> (itemStart - visibleStart - edgeMarginPx).toFloat()
        itemEnd > visibleEnd -> (itemEnd - visibleEnd + edgeMarginPx).toFloat()
        else -> 0f
    }
}
