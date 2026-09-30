package com.github.kevinvane.daytimeline.library.core

/**
 * 增量更新与滚动位置保持（PRD FD-002 / FD-003 / AC-06）。
 *
 * 纯 Kotlin，不依赖 Android，因此可直接单测——这正是「无闪烁、无跳变、无重排」
 * 三个要求能被自动化验证的前提。
 */
object EventDiff {

    /**
     * 计算滚动锚点。
     *
     * 滚动位置不能存绝对像素（FD-003：数据变更后滚动位置不跳变），因为数据一变化，
     * 同一像素位置对应的日程就变了。正确做法是记录「锚点日程 + 它相对视口顶部的偏移」，
     * 更新后若锚点仍在则精确还原。
     */
    fun anchorOf(
        blocks: List<PlacedBlock>,
        scrollOffset: Int,
        hourHeight: Int,
        topPadding: Int,
    ): ScrollAnchor? {
        if (blocks.isEmpty() || hourHeight <= 0) return null
        // 视口顶部落在哪条块上
        for (b in blocks) {
            val top = topPadding + Geometry.minuteToOffset(b.event.start.minuteOfDay.toFloat(), hourHeight).toInt()
            val height = Geometry.blockHeight(
                b.event.start.minuteOfDay, b.event.end.minuteOfDay, hourHeight, 0,
            )
            val bottom = top + height
            val viewportBottom = scrollOffset + topPadding
            if (bottom > viewportBottom) {
                return ScrollAnchor(eventId = b.event.id, offsetInViewport = top - scrollOffset)
            }
        }
        return null
    }

    /**
     * 按锚点还原滚动偏移。
     *
     * 锚点日程已消失（E20 同类情况）时返回 null，由调用方退化为「保持绝对偏移」。
     */
    fun restoreOffset(
        anchor: ScrollAnchor?,
        blocks: List<PlacedBlock>,
        hourHeight: Int,
        topPadding: Int,
        fallback: Int,
    ): Int {
        if (anchor == null || hourHeight <= 0) return fallback
        val target = blocks.firstOrNull { it.event.id == anchor.eventId } ?: return fallback
        val top = topPadding +
            Geometry.minuteToOffset(target.event.start.minuteOfDay.toFloat(), hourHeight).toInt()
        return (top - anchor.offsetInViewport).coerceAtLeast(0)
    }

    /** 判断新旧列表中，某个 id 是否指向不同的时间范围（PRD E28 冲突判定）。 */
    fun sameRange(a: SanitizedEvent?, b: SanitizedEvent?): Boolean {
        if (a == null || b == null) return a === b
        return a.start.minuteOfDay == b.start.minuteOfDay &&
            a.end.minuteOfDay == b.end.minuteOfDay
    }
}

/** 滚动锚点：某条日程的 id 及其相对视口顶部的像素偏移。 */
data class ScrollAnchor(val eventId: String, val offsetInViewport: Int)
