package com.github.kevinvane.daytimeline.library.api

/**
 * 业务方表单提交回来的值（PRD §7.7.1 / AD-22）。
 *
 * ## 与 [EditDraft] 成对
 *
 * 构成对称契约：`EditDraft`（组件 → 业务方，用于预填）→ 业务方改表单 →
 * `EditResult`（业务方 → 组件）→ `DayTimelineView.applyEdit` 灌回草稿 →
 * `confirmEdit()` 提交。
 *
 * @property range 表单里填的起止时间。**非法值会被组件合法化**：
 *   吸附到步长、越界钳制到全天、两端填反则交换、最短/最长时长兜底，
 *   与拖拽走同一套规则且两端对称（PRD §8.3.1 / D11）。
 * @property content 新标题。
 *
 *   **三态语义，务必分清**：
 *
 *   | 取值 | 含义 |
 *   |---|---|
 *   | `null` | **不改标题**，沿用 [EditDraft.content] |
 *   | `""` | **清空标题**（PRD 允许空标题；块内不显示文字，读屏用占位文案） |
 *   | 其它 | 设为该标题 |
 *
 *   之所以用 `""` 而不是 `null` 表示清空：`null` 已占用了「不改」的语义，
 *   若再让它兼任「清空」，用户就永远无法把标题删空。表单里请把空输入框
 *   映射为 `""` 而不是 `null`。
 */
data class EditResult(
    val range: IntRange,
    val content: CharSequence? = null,
)
