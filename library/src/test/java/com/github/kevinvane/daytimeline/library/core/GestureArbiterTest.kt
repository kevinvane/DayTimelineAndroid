package com.github.kevinvane.daytimeline.library.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD §8.2 / AD-04：手势判定与滚动模式下的手势归属。 */
class GestureArbiterTest {

    private val arbiter = GestureArbiter(dragThresholdPx = 30)

    // ---------- 点击 / 长按 / 滚动互斥（§8.2） ----------

    @Test
    fun `未移动且未长按时判为点击`() {
        arbiter.onDown(100f, 500f, editingTouching = false)
        assertEquals(GestureArbiter.Intent.Click, arbiter.onUp())
    }

    @Test
    fun `已触发长按但全程未移动时进入编辑态`() {
        arbiter.onDown(100f, 500f, editingTouching = false)
        assertTrue(arbiter.onLongPressTimeout())
        assertEquals(GestureArbiter.Intent.LongPress, arbiter.onUp())
    }

    @Test
    fun `超过阈值判为滚动且不可回退为点击`() {
        arbiter.onDown(100f, 500f, editingTouching = false)
        arbiter.onMove(100f, 600f, grabbed = 0)
        assertEquals(GestureArbiter.Intent.Scroll, arbiter.onUp())
    }

    @Test
    fun `滚动后长按超时不生效`() {
        arbiter.onDown(100f, 500f, editingTouching = false)
        arbiter.onMove(100f, 600f, grabbed = 0)
        assertEquals(false, arbiter.onLongPressTimeout())
    }

    @Test
    fun `未超过阈值时仍保持未判定`() {
        arbiter.onDown(100f, 500f, editingTouching = false)
        arbiter.onMove(105f, 505f, grabbed = 0)
        assertEquals(GestureArbiter.Intent.Pending, arbiter.intent)
    }

    // ---------- AD-04：外部滚动模式不抢手势（E29） ----------

    @Test
    fun `非编辑态的纵向移动判为滚动因此不消费手势`() {
        arbiter.onDown(100f, 500f, editingTouching = false)
        arbiter.onMove(100f, 700f, grabbed = 0)
        assertEquals(GestureArbiter.Intent.Scroll, arbiter.intent)
        assertTrue("必须把手势让给外层容器", !arbiter.consumesGesture())
    }

    @Test
    fun `编辑态中的纵向移动被消费而不判为滚动`() {
        arbiter.onDown(100f, 500f, editingTouching = true)
        arbiter.onMove(100f, 700f, grabbed = 0)
        assertEquals(GestureArbiter.Intent.DragMove, arbiter.intent)
        assertTrue("编辑态拖拽必须消费手势", arbiter.consumesGesture())
    }

    // ---------- 手柄拖拽 ----------

    @Test
    fun `抓住上边缘判为调整开始时间`() {
        arbiter.onDown(100f, 500f, editingTouching = true)
        arbiter.onMove(100f, 560f, grabbed = 1)
        assertEquals(GestureArbiter.Intent.DragResizeTop, arbiter.onUp())
    }

    @Test
    fun `抓住下边缘判为调整结束时间`() {
        arbiter.onDown(100f, 500f, editingTouching = true)
        arbiter.onMove(100f, 560f, grabbed = -1)
        assertEquals(GestureArbiter.Intent.DragResizeBottom, arbiter.onUp())
    }

    @Test
    fun `手柄拖拽优先于整体移动`() {
        arbiter.onDown(100f, 500f, editingTouching = true)
        arbiter.onMove(100f, 560f, grabbed = 1)
        assertEquals(GestureArbiter.Intent.DragResizeTop, arbiter.intent)
    }

    // ---------- 重新按下应重置 ----------

    @Test
    fun `新的按下会重置上一次的判定`() {
        arbiter.onDown(100f, 500f, editingTouching = false)
        arbiter.onMove(100f, 700f, grabbed = 0)
        assertEquals(GestureArbiter.Intent.Scroll, arbiter.intent)
        arbiter.onDown(100f, 500f, editingTouching = false)
        assertEquals(GestureArbiter.Intent.Pending, arbiter.intent)
        assertEquals(GestureArbiter.Intent.Click, arbiter.onUp())
    }
}
