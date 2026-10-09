package com.github.kevinvane.daytimeline.library.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD §9.3 / E3–E7：脏数据容错与上抛。 */
class EventSanitizerTest {

    // ---------- E3 ----------
    @Test
    fun `E3 结束时间不晚于开始时间时按最短时长修正并上抛`() {
        val r = EventSanitizer.sanitize(
            listOf(TestEvent("a", 600, 600), TestEvent("b", 600, 500)),
            minDurationMinutes = 5,
        )
        assertEquals(
            listOf(DataIssue.Kind.NON_POSITIVE_DURATION, DataIssue.Kind.NON_POSITIVE_DURATION),
            r.issues.map { it.kind },
        )
        r.events.forEach {
            assertTrue("修正后结束必须晚于开始", it.end.isStrictlyAfter(it.start))
            assertEquals(5, it.end.minuteOfDay - it.start.minuteOfDay)
        }
    }

    @Test
    fun `E3 起点贴近 24 点时改为向前回退以保证最短时长`() {
        // 1438 分开始、请求 5 分钟最短时长 → 放不下，改为 [1435, 1440]
        val r = EventSanitizer.sanitize(
            listOf(TestEvent("a", 1438, 1438)),
            minDurationMinutes = 5,
        )
        val e = r.events.single()
        assertEquals(1435, e.start.minuteOfDay)
        assertEquals(1440, e.end.minuteOfDay)
    }

    // ---------- E4 ----------
    @Test
    fun `E4 结束时间晚于 24 点时截断并上抛`() {
        val r = EventSanitizer.sanitize(listOf(TestEvent("a", 1380, 2000)))
        assertEquals(listOf(DataIssue.Kind.END_AFTER_MIDNIGHT), r.issues.map { it.kind })
        assertEquals(1440, r.events.single().end.minuteOfDay)
    }

    // ---------- E5 ----------
    @Test
    fun `E5 开始时间早于 0 点时修正并上抛`() {
        val r = EventSanitizer.sanitize(listOf(TestEvent("a", -60, 600)))
        assertEquals(listOf(DataIssue.Kind.START_BEFORE_MIDNIGHT), r.issues.map { it.kind })
        assertEquals(0, r.events.single().start.minuteOfDay)
    }

    // ---------- E7 ----------
    @Test
    fun `E7 重复标识正常显示不去重并上抛`() {
        val r = EventSanitizer.sanitize(
            listOf(TestEvent("dup", 540, 600), TestEvent("dup", 660, 720)),
        )
        assertEquals(listOf(DataIssue.Kind.DUPLICATE_ID), r.issues.map { it.kind })
        // 两条都保留
        assertEquals(2, r.events.size)
    }

    @Test
    fun `空标识被记录但不丢弃数据`() {
        val r = EventSanitizer.sanitize(listOf(TestEvent("", 540, 600)))
        assertEquals(listOf(DataIssue.Kind.BLANK_ID), r.issues.map { it.kind })
        assertEquals(1, r.events.size)
    }

    // ---------- 合法数据 ----------
    @Test
    fun `合法数据不产生任何异常`() {
        val r = EventSanitizer.sanitize(
            listOf(TestEvent("a", 540, 600), TestEvent("b", 660, 720)),
        )
        assertTrue(r.issues.isEmpty())
    }

    // ---------- E1 / E2 ----------
    @Test
    fun `E1 E2 空输入与空列表不报错`() {
        assertTrue(EventSanitizer.sanitize(emptyList()).events.isEmpty())
        assertTrue(EventSanitizer.sanitize(emptyList()).issues.isEmpty())
    }

    // ---------- 最长时长兜底 ----------
    @Test
    fun `超过最长时长时按最长时长截断`() {
        val r = EventSanitizer.sanitize(
            listOf(TestEvent("a", 0, 1440)),
            maxDurationMinutes = 60,
        )
        val e = r.events.single()
        assertEquals(60, e.end.minuteOfDay - e.start.minuteOfDay)
    }

    @Test
    fun `全部修正后的数据都落在 0 点到 24 点之间且时长为正`() {
        // 随机脏数据不得让任何一条逃出合法区间（这是 Q4 不崩溃的几何前提）
        val r = EventSanitizer.sanitize(
            listOf(
                TestEvent("a", -100, -50),
                TestEvent("b", 500, 400),
                TestEvent("c", 2000, 3000),
                TestEvent("d", 1439, 1441),
                TestEvent("e", 700, 700),
            ),
        )
        r.events.forEach {
            assertTrue(it.start.minuteOfDay in 0..1440)
            assertTrue(it.end.minuteOfDay in 0..1440)
            assertTrue(it.end.isStrictlyAfter(it.start))
        }
    }
}
