package com.github.kevinvane.daytimeline.library.core

/**
 * 触摸命中测试（PRD FI-015 / Q10 / §8.2）。
 *
 * 核心矛盾：**视觉可以很小，触摸热区不能小**。
 * - 编辑手柄视觉直径 6dp，热区必须 48×48dp（UF-001）
 * - 高度不足 48dp 的日程块，点击热区自动纵向扩展到 48dp
 *
 * 因此命中测试必须在**独立于绘制**的坐标上做，绘制小不影响点得中。
 * 纯 Kotlin，无 Android 依赖，可直接单测。
 */
class HitTester(
    /** 最小触摸目标（px），来自 dimens.minTouchTarget。 */
    private val minTouchTarget: Int,
    /** 编辑手柄触摸热区（px），来自 dimens.handleTouchSize。 */
    private val handleTouchSize: Int,
) {

    /** 命中结果。 */
    sealed class Hit {
        /** 命中某个日程块。 */
        data class Block(val block: PlacedBlock) : Hit()

        /** 命中编辑块的上手柄。 */
        object TopHandle : Hit()

        /** 命中编辑块的下手柄。 */
        object BottomHandle : Hit()

        /** 命中空白区域（可用于新建，FI-005）。 */
        object Empty : Hit()
    }

    /**
     * @param y 视口坐标下的触摸 y。
     * @param contentLeft 内容区左边缘 x（已按布局方向解析）。
     * @param contentRight 内容区右边缘 x。
     * @param blocks 全部布局结果。
     * @param blockTops 每个块顶边的 y（视口坐标，已扣滚动偏移），与 blocks 同序。
     * @param blockHeights 每个块高度，与 blocks 同序。
     * @param editing 编辑态块的下标；无编辑态为 -1。
     * @param editingTop 编辑块顶边 y；无编辑态时忽略。
     * @param editingHeight 编辑块高度；无编辑态时忽略。
     */
    fun hitTest(
        x: Float,
        y: Float,
        contentLeft: Int,
        contentRight: Int,
        blocks: List<PlacedBlock>,
        blockTops: IntArray,
        blockHeights: IntArray,
        editing: Int = -1,
        editingTop: Int = 0,
        editingHeight: Int = 0,
    ): Hit {
        // ---- 1. 先判编辑手柄（热区远大于视觉，且优先级最高） ----
        if (editing >= 0) {
            val topHandleY = editingTop
            val bottomHandleY = editingTop + editingHeight
            // 手柄居中于上下边缘水平中心（胶囊形，§7.7）
            val handleX = (contentLeft + contentRight) / 2f
            val handleHit = handleTouchSize / 2
            if ((x - handleX).let { abs(it) } <= handleHit &&
                (y - topHandleY).let { abs(it) } <= handleHit
            ) {
                return Hit.TopHandle
            }
            if ((x - handleX).let { abs(it) } <= handleHit &&
                (y - bottomHandleY).let { abs(it) } <= handleHit
            ) {
                return Hit.BottomHandle
            }
        }

        // ---- 2. 命中日程块 ----
        if (x < contentLeft || x > contentRight) return Hit.Empty

        val minTarget = minTouchTarget
        for (i in blocks.indices) {
            val b = blocks[i]
            if (x < contentLeft + b.left || x > contentLeft + b.right) continue
            val top = blockTops[i]
            val height = blockHeights[i]
            val centerY = top + height / 2f
            // 高度不足 48dp 时热区纵向扩展到 48dp（FI-015）
            val halfHit = (maxOf(height, minTarget) / 2f)
            if ((y - centerY).let { abs(it) } <= halfHit) {
                return Hit.Block(b)
            }
        }
        return Hit.Empty
    }

    /** 视觉手柄很小，但热区是 [handleTouchSize]；该函数仅用于绘制可访问性矩形。 */
    fun handleHotRect(
        centerX: Float,
        centerY: Float,
        out: IntArray,
    ) {
        val half = handleTouchSize / 2
        out[0] = (centerX - half).toInt()
        out[1] = (centerY - half).toInt()
        out[2] = (centerX + half).toInt()
        out[3] = (centerY + half).toInt()
    }

    private fun abs(v: Float): Float = if (v < 0) -v else v
}
