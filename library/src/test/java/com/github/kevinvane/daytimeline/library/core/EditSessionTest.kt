package com.github.kevinvane.daytimeline.library.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 核心回归集。
 *
 * 重点是 **D3**：用户点击取消后绝对不得向业务方发出任何数据变更通知。
 * 该约束在 [EditSession.cancel] 的**类型**上就被保证（返回无字段的空对象），
 * 这里验证行为。
 */
class EditSessionTest {

    private fun event(id: String, start: Int, end: Int) =
        EventSanitizer.sanitize(listOf(TestEvent(id, start, end))).events.single()

    // ---------- D3 取消零事件（发布红线） ----------

    @Test
    fun `D3 取消编辑不产生任何数据变更结果`() {
        val session = EditSession.beginEdit(event("a", 540, 600))
        val result = session.cancel()
        // 返回类型上不可能承载数据变更信息
        assertEquals(EditSession.CancelResult, result)
    }

    @Test
    fun `D3 新建态取消同样不产生数据变更`() {
        val session = EditSession.beginCreate(600, 15, 60)
        assertEquals(EditSession.CancelResult, session.cancel())
    }

    @Test
    fun `D3 拖拽修改后再取消依然不产生数据变更`() {
        val session = EditSession.beginEdit(event("a", 540, 600))
        session.moveTo(700, snapMinutes = 15, minDuration = 5, maxDuration = 1440)
        assertEquals(EditSession.CancelResult, session.cancel())
    }

    @Test
    fun `commit 与 cancel 是互斥的唯一出口`() {
        val session = EditSession.beginEdit(event("a", 540, 600))
        val commit = session.commit()
        assertTrue(commit is EditSession.Commit.Modify)
        val modified = commit as EditSession.Commit.Modify
        assertEquals("a", modified.event.id)
        assertEquals(540..600, modified.range)
    }

    // ---------- 新建 vs 修改 ----------

    @Test
    fun `空白处新建默认 1 小时且起点吸附`() {
        val s = EditSession.beginCreate(607, snapMinutes = 15, defaultDurationMinutes = 60)
        assertTrue(s.isCreating)
        assertEquals(0, s.startMinute % 15)
        assertEquals(60, s.endMinute - s.startMinute)
        assertTrue(s.commit() is EditSession.Commit.Create)
    }

    @Test
    fun `长按已有日程进入修改态`() {
        val s = EditSession.beginEdit(event("a", 540, 600))
        assertFalse(s.isCreating)
        assertTrue(s.commit() is EditSession.Commit.Modify)
    }

    // ---------- 拖拽（§8.3） ----------

    @Test
    fun `拖拽移动保持时长`() {
        val s = EditSession.beginEdit(event("a", 540, 600))
        s.moveTo(700, 15, 5, 1440)
        assertEquals(60, s.endMinute - s.startMinute)
        assertEquals(0, s.startMinute % 15)
    }

    @Test
    fun `拖上边缘改结束时间并保证最短时长`() {
        val s = EditSession.beginEdit(event("a", 600, 700))
        s.resizeEndTo(620, 15, 5)
        assertEquals(615, s.endMinute)
        s.resizeEndTo(601, 0, 5)
        assertEquals(605, s.endMinute) // 短于最小时长自动兜底
    }

    @Test
    fun `拖下边缘改开始时间且不晚于结束时间`() {
        val s = EditSession.beginEdit(event("a", 600, 700))
        s.resizeStartTo(620, 15, 5)
        assertEquals(615, s.startMinute)
        s.resizeStartTo(699, 0, 5)
        assertEquals(695, s.startMinute) // 不得使时长短于最小时长
    }

    // ---------- E20 / E21 / E28 ----------

    @Test
    fun `E20 被编辑的日程从列表消失时自动取消编辑态`() {
        val target = event("a", 540, 600)
        val s = EditSession.beginEdit(target)
        // 新列表里已经没有 a
        val kept = s.onDataChanged(mapOf("b" to event("b", 700, 800)))
        assertNull("引用已失效必须自动取消编辑", kept)
    }

    @Test
    fun `E21 新列表不含被编辑日程时保持编辑态不变`() {
        val target = event("a", 540, 600)
        val s = EditSession.beginEdit(target)
        s.moveTo(720, 15, 5, 1440)
        val kept = s.onDataChanged(mapOf("a" to target, "b" to event("b", 700, 800)))
        assertTrue(kept === s)
        // 用户操作未被打断
        assertEquals(720, s.startMinute)
        assertFalse("无关变更不应置冲突标记", s.hasConflict)
    }

    @Test
    fun `E28 被编辑的日程本身被改动时保留编辑态并置冲突标记`() {
        val target = event("a", 540, 600)
        val s = EditSession.beginEdit(target)
        s.moveTo(720, 15, 5, 1440)
        // 业务方把 a 改到了别的时间
        val kept = s.onDataChanged(mapOf("a" to event("a", 900, 960)))
        assertTrue(kept === s)
        assertTrue(s.hasConflict)
        val commit = s.commit() as EditSession.Commit.Modify
        assertTrue("完成时必须把冲突标记带给业务方", commit.hasConflict)
    }

    @Test
    fun `新建态不受列表变更影响`() {
        val s = EditSession.beginCreate(600, 15, 60)
        val kept = s.onDataChanged(emptyMap())
        assertTrue(kept === s)
    }

    @Test
    fun `列表中时间未变则不置冲突标记`() {
        val target = event("a", 540, 600)
        val s = EditSession.beginEdit(target)
        val kept = s.onDataChanged(mapOf("a" to event("a", 540, 600)))
        assertTrue(kept === s)
        assertFalse(s.hasConflict)
    }

    // ---------- applyEdit：业务方表单一次性改两端 + 标题（AD-22）----------

    @Test
    fun `applyEdit 起止时间走同一套合法化规则`() {
        val s = EditSession.beginEdit(event("a", 540, 600))
        // 步长 0，两端合法 → 原样落地
        s.applyEdit(660, 780, snapMinutes = 0, minDuration = 5, maxDuration = 1440, newContent = null)
        assertEquals(660, s.startMinute)
        assertEquals(780, s.endMinute)
    }

    @Test
    fun `applyEdit 越界与填反都被兜底`() {
        val s = EditSession.beginEdit(event("a", 540, 600))
        s.applyEdit(1530, 1560, snapMinutes = 0, minDuration = 5, maxDuration = 1440, newContent = null)
        assertTrue("不得越界，实际 ${s.startMinute}..${s.endMinute}", s.endMinute <= 1440)
        assertTrue("不得越界，实际 ${s.startMinute}..${s.endMinute}", s.startMinute >= 0)

        val t = EditSession.beginEdit(event("a", 540, 600))
        t.applyEdit(700, 600, snapMinutes = 0, minDuration = 5, maxDuration = 1440, newContent = null)
        assertEquals("填反应被交换", 600, t.startMinute)
        assertEquals(700, t.endMinute)
    }

    /** 修改态草稿以原标题起步（供表单预填）。 */
    @Test
    fun `修改态草稿以原标题起步`() {
        val target = EventSanitizer.sanitize(
            listOf(TestEvent("a", 540, 600, content = "旧标题")),
        ).events.single()
        val s = EditSession.beginEdit(target)
        assertEquals("旧标题", s.pendingContent?.toString())
    }

    /** 三态语义：`null` = 不改，`""` = 清空（AD-22 / PRD 允许空标题）。 */
    @Test
    fun `applyEdit 的 null 表示不改而空串表示清空`() {
        val target = EventSanitizer.sanitize(
            listOf(TestEvent("a", 540, 600, content = "旧标题")),
        ).events.single()
        val s = EditSession.beginEdit(target)

        s.applyEdit(540, 600, snapMinutes = 0, minDuration = 5, maxDuration = 1440, newContent = null)
        assertEquals("null 应沿用原标题", "旧标题", s.pendingContent?.toString())

        s.applyEdit(540, 600, snapMinutes = 0, minDuration = 5, maxDuration = 1440, newContent = "")
        assertEquals("空串应清空标题", "", s.pendingContent?.toString())
    }

    /** 提交结果必须带上待提交的标题，否则标题改了也没人知道。 */
    @Test
    fun `commit 携带表单提交的标题`() {
        val target = EventSanitizer.sanitize(
            listOf(TestEvent("a", 540, 600, content = "旧标题")),
        ).events.single()
        val s = EditSession.beginEdit(target)
        s.applyEdit(600, 660, snapMinutes = 0, minDuration = 5, maxDuration = 1440, newContent = "新标题")

        val commit = s.commit() as EditSession.Commit.Modify
        assertEquals("新标题", commit.content?.toString())
        assertEquals(600..660, commit.range)
    }

    @Test
    fun `新建态 applyEdit 后 commit 携带标题`() {
        val s = EditSession.beginCreate(600, 0, 60)
        s.applyEdit(600, 660, snapMinutes = 0, minDuration = 5, maxDuration = 1440, newContent = "新日程")
        val commit = s.commit() as EditSession.Commit.Create
        assertEquals("新日程", commit.content?.toString())
        assertEquals(600, commit.startMinute)
        assertEquals(660, commit.endMinute)
    }

    /** applyEdit 之后取消仍不得产生任何数据变更（D3 在表单路径上同样成立）。 */
    @Test
    fun `D3 applyEdit 之后取消依然零事件`() {
        val s = EditSession.beginEdit(event("a", 540, 600))
        s.applyEdit(600, 660, snapMinutes = 0, minDuration = 5, maxDuration = 1440, newContent = "改了")
        assertTrue("cancel 恒返回无字段对象", s.cancel() is EditSession.CancelResult)
    }
}
