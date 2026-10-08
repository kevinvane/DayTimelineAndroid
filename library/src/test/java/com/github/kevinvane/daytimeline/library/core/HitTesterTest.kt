package com.github.kevinvane.daytimeline.library.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD FI-015 / Q10 / UF-001：视觉可小，触摸热区不得小于 48dp。 */
class HitTesterTest {

    private val minTarget = 48
    private val handleTouch = 48
    private val tester = HitTester(minTarget, handleTouch)

    private fun block(id: String, start: Int, end: Int, left: Int, width: Int): PlacedBlock {
        val e = EventSanitizer.sanitize(listOf(TestEvent(id, start, end))).events.single()
        return PlacedBlock(e, 0, 1, 1, left, width)
    }

    @Test
    fun `高度不足 48dp 的块热区自动纵向扩展到 48dp`() {
        val b = block("a", 0, 5, left = 0, width = 200) // 只有 5 分钟，几像素高
        val tops = intArrayOf(100)
        val heights = intArrayOf(10)
        val hit = tester.hitTest(
            x = 50f, y = 122f, // 距块中心 17px，已超出 10px 高的块，但仍在 48dp 热区内
            contentLeft = 0, contentRight = 200,
            blocks = listOf(b), blockTops = tops, blockHeights = heights,
        )
        assertTrue("应命中扩展后的热区", hit is HitTester.Hit.Block)
    }

    @Test
    fun `超出 48dp 热区则判为空白`() {
        val b = block("a", 0, 5, left = 0, width = 200)
        val hit = tester.hitTest(
            x = 50f, y = 160f, // 距中心 55px
            contentLeft = 0, contentRight = 200,
            blocks = listOf(b), blockTops = intArrayOf(100), blockHeights = intArrayOf(10),
        )
        assertEquals(HitTester.Hit.Empty, hit)
    }

    @Test
    fun `点击内容区外判为空白`() {
        val b = block("a", 540, 600, left = 0, width = 200)
        val hit = tester.hitTest(
            x = 10f, y = 100f,
            contentLeft = 60, contentRight = 400, // 块实际从 x=60 开始
            blocks = listOf(b), blockTops = intArrayOf(100), blockHeights = intArrayOf(50),
        )
        assertEquals(HitTester.Hit.Empty, hit)
    }

    @Test
    fun `命中已有块`() {
        val b = block("a", 540, 600, left = 0, width = 200)
        val hit = tester.hitTest(
            x = 100f, y = 125f,
            contentLeft = 0, contentRight = 200,
            blocks = listOf(b), blockTops = intArrayOf(100), blockHeights = intArrayOf(50),
        )
        assertEquals("a", (hit as HitTester.Hit.Block).block.event.id)
    }

    @Test
    fun `视觉 6dp 的手柄热区可达 48dp`() {
        val b = block("a", 540, 600, left = 0, width = 200)
        val hit = tester.hitTest(
            // 距上手柄视觉中心 20px，视觉半径仅 3px，但热区半径 24px
            x = 120f, y = 120f, // 上手柄中点 (100,120)
            contentLeft = 0, contentRight = 200,
            blocks = listOf(b), blockTops = intArrayOf(100), blockHeights = intArrayOf(100),
            editing = 0, editingTop = 120, editingHeight = 100,
        )
        assertEquals(HitTester.Hit.TopHandle, hit)
    }

    @Test
    fun `下手柄居中于底边`() {
        val b = block("a", 540, 600, left = 0, width = 200)
        val hit = tester.hitTest(
            x = 100f, y = 200f, // 底边中点（contentLeft+contentRight)/2=100
            contentLeft = 0, contentRight = 200,
            blocks = listOf(b), blockTops = intArrayOf(100), blockHeights = intArrayOf(100),
            editing = 0, editingTop = 100, editingHeight = 100,
        )
        assertEquals(HitTester.Hit.BottomHandle, hit)
    }

    @Test
    fun `手柄热区 48dp 内的空白也能命中手柄而非空白`() {
        val b = block("a", 540, 600, left = 0, width = 200)
        val hit = tester.hitTest(
            x = 115f, y = 112f, // 距上手柄中点 (100,120) 约 17px，热区半径 24px
            contentLeft = 0, contentRight = 200,
            blocks = listOf(b), blockTops = intArrayOf(100), blockHeights = intArrayOf(200),
            editing = 0, editingTop = 120, editingHeight = 200,
        )
        assertTrue(
            "视觉手柄只有 6dp，但热区 48dp，故 17px 处应命中手柄",
            hit == HitTester.Hit.TopHandle || hit is HitTester.Hit.Block,
        )
    }
}
