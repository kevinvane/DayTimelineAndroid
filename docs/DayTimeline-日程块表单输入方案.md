# DayTimeline · 日程块表单输入方案

| 项 | 内容 |
|---|---|
| 文档版本 | v0.1 |
| 状态 | 草案，待实现 |
| 编写日期 | 2026-10-09 |
| 文档类型 | 设计方案（落地 PRD v1.3 §7.7 / §10.1 / §11.3 与技术方案 AD-22） |
| 上游依据 | PRD v1.3、技术方案 AD-08（第四层定制）、AD-07（编辑态存活） |

---

## 1. 背景与边界判定

### 1.1 现状：组件没有表单，也不该有

当前 `library` 的编辑能力全部是手势驱动的就地时间调整：点空白新建草稿、长按进入修改、拖块 / 拖手柄改起止时间。库内唯一的 Dialog 是删除二次确认（`DayTimelineView.kt` 的 `defaultConfirmDelete`）。

这不是遗漏，而是 PRD 划定的边界：

| PRD 条款 | 原文要点 | 推论 |
|---|---|---|
| §7.7 编辑态规格 | 只列了手柄、起止时间文字、完成/取消、删除 | 无文本输入项 |
| §10.1 可配置项总览 | 「编辑交互」只列完成/取消/删除按钮 | 无文本框 |
| §10.2 第四层 | 「接管新建流程，**改为自己弹出底部面板**」 | 表单的落点已指定 |
| §11.3 事件表 | 新建只携带「起止时间」；修改携带「原起止、新起止」 | 不带内容 |
| §9.1 数据契约 | 显示内容「由业务方自定」，且不侵入业务模型 | 内容的所有权在业务方 |

**结论：表单输入是 PRD v1.3 新增的能力，落点是 PRD 既定的第四层「交互接管」。**

### 1.2 现有第四层载荷不够用

`DayTimelineView.EditController` 当前签名：

```kotlin
interface EditController {
    fun onDone(range: IntRange, isCreating: Boolean): Boolean = false
    fun onCancel() = Unit
    fun onDelete(): Boolean = false
    fun confirmDelete(context: Context, eventTitle: String, onConfirmed: () -> Unit)
}
```

业务方要弹表单弹不出来，卡在六个缺口上：

| 编号 | 缺口 | 后果 |
|---|---|---|
| G1 | 没有「进入编辑态」钩子 | 无法得知该弹表单的时机，只能轮询 `isEditing()` 或从 `onEventLongClick` 猜 |
| G2 | `onDone` 不带被编辑的日程 | 只有 `IntRange` 与 `isCreating`，不知道在改哪一条 |
| G3 | 草稿不可读 | `EditSession` 是 View 私有字段，**无法预填表单**的现有标题与时间 |
| G4 | 无法回传新值 | 载荷只有 `IntRange`，标题 / 颜色无处可放 |
| G5 | `onEventModified` 回传修改前的对象 | 标题在编辑路径上根本改不了 |
| G6 | FI-010 与外部表单冲突 | 「点外部区域视为取消」在表单是组件外部窗口时语义不成立，见 §7 |

---

## 2. 总体架构决策

> **组件零新增 View，只把 `EditController` 的载荷补成对称的双向契约。**

### 2.1 为什么不做内置表单

考虑过「组件内置一个可选底部面板」，否决。理由：

| 约束 | 内置表单的冲突 |
|---|---|
| PRD §12.1 | 「时间轴网格区常驻界面元素数必须为 0」——内置面板要引入 View 树，指标口径要重新界定 |
| PRD D7 | 第二至四层必须提供入口，不允许业务方改或复制组件源码——但内置表单会把业务字段（标题/备注/地点）硬编码进组件 |
| PRD §10.1 | 可配置项总览是封闭清单，内置表单要新增一批开关 |
| §9.1 | 组件一旦接收 `note`/`location`，就得定义其生命周期、校验与持久化语义，等于接管业务模型 |
| K10 | 零第三方依赖——引入第二套主题体系与深色适配负担 |

### 2.2 为什么补载荷是够的

组件在表单这件事上只需要负责两件事：

1. **几何正确性**——业务方填的越界值、填反的两端、超长时长，必须被吸附与兜底。这是组件的固有职责。
2. **结果传递**——把「改了什么」如实上抛，不做取舍。

其余（表单长什么样、标题怎么校验、备注存哪）全部属于业务方。这是 §9.1「不侵入业务模型」的直接推论。

---

## 3. 字段归属矩阵

| 字段 | 组件参与 | 载体 | 理由 |
|---|:--:|---|---|
| 起止时间 | 参与 | `EditResult.range` | 几何正确性（吸附 / 兜底 / D11 对称）是组件的活 |
| 标题 | 参与 | `EditResult.content` ↔ `TimelineEvent.content` | 唯一有组件语义的内容字段，映射到块内显示与读屏 |
| 颜色 | 参与 | `TimelineEvent.color` | 要画到块上，必须有通道（§5） |
| 备注 / 地点 | **零参与** | — | 业务方自己收、自己存。若要显示在块内，用第三层 `EventBlockPainter` 或拼进 `content` |
| 其他业务字段 | **零参与** | — | 同上 |

> **「零参与」不是缩水，是范围裁剪。** 组件一旦开始接收备注/地点，就必须为它们定义校验规则、生命周期与冲突语义——那是业务模型，不是渲染型组件该管的事。

---

## 4. API 设计

新增类型放 `library/api/`（对外契约），算法放 `library/core/`（纯 Kotlin）。

### 4.1 EditDraft —— 组件 → 业务方

```kotlin
// api/EditDraft.kt（新增）
package com.github.kevinvane.daytimeline.library.api

import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent

/**
 * 进入编辑态时交给业务方的快照，用于预填表单。
 *
 * 补的是缺口 G3：此前 EditSession 是 View 私有字段，业务方读不到草稿。
 *
 * 不可变：拿到的是进入编辑态那一刻的值。用户在时间轴上拖拽后草稿会变，
 * 但那属于组件内部状态，不回推给本对象。
 */
data class EditDraft(
    /** 新建为 true，修改为 false。 */
    val isCreating: Boolean,
    /** 修改态：修改前的日程对象；新建态为 null。 */
    val event: TimelineEvent?,
    /** 当前起止时间（已吸附、已兜底、已钳制到 [0, 1440]）。 */
    val range: IntRange,
    /** 当前显示内容；新建态为 null。 */
    val content: CharSequence?,
)

/** 便利方法：起止分钟值类型形式，避免业务方接触裸 Int（PRD D18/D19）。 */
val EditDraft.startMinute: MinuteOfDay get() = MinuteOfDay.ofMinute(range.first)
val EditDraft.endMinute: MinuteOfDay get() = MinuteOfDay.ofMinute(range.last)
```

### 4.2 EditResult —— 业务方 → 组件

```kotlin
// api/EditResult.kt（新增）
/**
 * 业务方表单提交回来的值。
 *
 * 与 EditDraft 成对，构成「组件给出草稿 → 业务方改 → 组件提交结果」的对称契约。
 */
data class EditResult(
    /** 表单里填的起止时间。非法值会被吸附与兜底（见 §6）。 */
    val range: IntRange,
    /** 新标题；null 表示不改标题，沿用 EditDraft.content。 */
    val content: CharSequence? = null,
)
```

**颜色为什么不在 `EditResult` 里**：改色后组件没有可靠途径做乐观同步——`replaceLocally()` 同步的是 `SanitizedEvent` 的 `start`/`end`，而 `content` 与 `color` 都住在 `source`（业务方对象）上，改不了。与 `content` 一致，走业务方 `submitEvents()` 回传。

### 4.3 EditController 扩展

```kotlin
/** 第四层定制：接管新建流程 / 编辑层操作。 */
interface EditController {

    /**
     * 进入编辑态（新建或修改）。返回 true 表示业务方接管。
     *
     * 接管后组件不再响应 FI-010（点外部区域视为取消），退出路径完全交给
     * 业务方——由业务方在表单关闭时调用 confirmEdit() 或 cancelEdit()。
     * 见 §7。
     */
    fun onEnterEditing(draft: EditDraft): Boolean = false

    /** 用户点击完成。返回 true 表示已由业务方处理，组件不再自行上抛事件。 */
    fun onDone(draft: EditDraft, result: EditResult): Boolean = false

    /** 用户点击取消。组件内部已保证不会发出任何数据变更。 */
    fun onCancel(draft: EditDraft) = Unit

    /** 用户点击删除。 */
    fun onDelete(): Boolean = false

    /** 需要弹出删除二次确认。组件提供默认实现，业务方可覆盖。 */
    fun confirmDelete(context: Context, eventTitle: String, onConfirmed: () -> Unit)
}
```

**破坏性变更**：`onDone` 与 `onCancel` 签名变了。库尚未发布（技术方案 §9.1 的 M6 未完成），现在改代价最小。

### 4.4 DayTimelineView 新增一个出口

```kotlin
/**
 * 把业务方表单提交的值灌回编辑草稿。
 *
 * 不发任何事件，只改草稿。提交仍必须由 confirmEdit() 触发——
 * 它是唯一的数据变更出口（与 cancelEdit() 严格镜像，见 D3）。
 *
 * 典型用法：
 *   onEnterEditing = { draft -> showMyForm(draft); true }
 *   // 业务方表单「确定」：
 *   view.applyEdit(EditResult(range = …, content = …))
 *   view.confirmEdit()
 *   // 业务方表单「取消」：
 *   view.cancelEdit()
 */
fun applyEdit(result: EditResult)
```

为什么不让 `applyEdit` 直接提交（一步到位）：保持「灌值」与「提交」分离，业务方才能实现「表单里改了时间但又改回去」这类中间态，且 `confirmEdit()` 仍是唯一可能发出数据变更的方法——D3 的结构性保证不被削弱。

**值域处理**：走 §6 的 `SnapCalculator.applyRange()`，与拖拽同一套规则。业务方填越界值、把两端填反、超长，都会被兜底，不会产生非法草稿。

### 4.5 TimelineListener 扩展

```kotlin
/** 日程新建完成。@param content 新建的显示内容；null 表示未填。 */
fun onEventCreated(range: IntRange, content: CharSequence? = null) = Unit

/** 日程修改完成。
 *  @param content 修改后的显示内容；null 表示未改标题，沿用原值。 */
fun onEventModified(
    event: TimelineEvent,
    range: IntRange,
    content: CharSequence? = null,
    hasConflict: Boolean,
) = Unit
```

`content` 给默认值 `null`，既补齐缺口 G5，又保持既有实现者可编译。

---

## 5. 颜色：第三条通道

### 5.1 问题

§7.3 的 17 项语义色项是组件主题色（浅/深两套、代码内禁止分支、AD-09）。业务色（每条日程一个颜色）目前完全没有表达通道：`TimelineEvent` 只有 `id`/`start`/`end`/`content`/`expiredOverride`。

### 5.2 做法

不让业务方覆盖主题色项，另开一条通道：

```kotlin
// core/TimelineEvent.kt
interface TimelineEvent {
    // …
    /**
     * 业务色（ARGB）。null 表示使用组件默认块配色。
     *
     * 刻意不并入 §7.3 的 17 项语义色项：那些是组件主题色，
     * 深浅两套取值由资源承担（AD-09），业务方覆盖会破坏深色适配。
     * 业务色是第三条通道，只影响日程块自身。
     *
     * 给了默认实现，既有 TimelineEvent 实现者零改动即可编译。
     */
    val color: Int? get() = null
}
```

### 5.3 链路

| 环节 | 改动 |
|---|---|
| `TimelineEvent.color` | 新增，默认 `null` |
| `SanitizedEvent.color` | 新增，`get() = source.color`（与 `content` 同构） |
| `BlockContext.accentColor` | 新增 `Int?`，默认 `null` |
| `DefaultPainters` | `accentColor != null` 时替代 `colorBlockAccent*`，否则行为不变 |
| `EventSanitizer` | **不校验**业务色——它不是时间数据，越界色值由业务方自己负责 |

**不做乐观同步**：`replaceLocally()` 只同步 range，改色后由业务方 `submitEvents()` 回传，与 `content` 现状一致。

### 5.4 深色模式的边界

业务色由业务方提供，组件**不做深浅转换**。若业务方需要深色下可读，应在自己的数据类里按 `Configuration.uiMode` 给出不同的 `color`。这与 §7.3「深色适配仅靠替换资源、不得写分支逻辑」不冲突——该约束约束的是**组件自身的主题色**，业务色不在其列。

---

## 6. 时间输入：双边对称的兜底

### 6.1 问题

业务方表单能填出 `25:30`、能把两端填反、能填超长时段。现有的 `snapResizeStart` / `snapResizeEnd` 是**单边**修正（一个固定开始改结束、一个固定结束改开始），只服务拖拽。表单一次改两端，需要双边对称入口——这是 **D11 的硬要求**。

### 6.2 新算法

```kotlin
// core/SnapCalculator.kt（新增，纯 Kotlin，必须配单测）
/**
 * 表单同时设定起止时间（第四层接管的表单会一次改两端）。
 *
 * 与 snapResizeStart / snapResizeEnd 的区别：那两个是单边修正，
 * 只服务于拖拽；本函数是双边对称的——两端按同一套规则吸附与兜底。
 *
 * 返回值保证不变式：0 <= start < end <= 1440 且 minDur <= end - start <= maxDur。
 */
fun applyRange(
    rawStart: Int,
    rawEnd: Int,
    stepMinutes: Int,
    minDurationMinutes: Int,
    maxDurationMinutes: Int,
): IntRange {
    val minDur = minDurationMinutes.coerceAtLeast(1)
    val maxDur = maxDurationMinutes.coerceIn(minDur, MinuteOfDay.MINUTES_PER_DAY)

    var start = snap(rawStart, stepMinutes).coerceIn(0, MinuteOfDay.END_OF_DAY_MINUTE)
    var end = snap(rawEnd, stepMinutes).coerceIn(0, MinuteOfDay.END_OF_DAY_MINUTE)

    // 容错：业务方把两端填反了
    if (start > end) { val t = start; start = end; end = t }

    // 最长时长：从开始处截断（与 dragTo 的 maxDuration 兜底一致）
    if (end - start > maxDur) end = start + maxDur

    // 最短时长：优先把结束往后推；越界则改为把开始往前拉
    if (end - start < minDur) {
        val pushed = start + minDur
        if (pushed <= MinuteOfDay.END_OF_DAY_MINUTE) {
            end = pushed
        } else {
            end = MinuteOfDay.END_OF_DAY_MINUTE
            start = (end - minDur).coerceAtLeast(0)
        }
    }
    return start..end
}
```

### 6.3 为什么放在 core

纯 Kotlin、零 `android.*` 依赖（AD-02 / T2），可直接 JVM 单测。按 AGENTS.md 的约定，改 `core/` 必须同步补单测，覆盖至少这些用例：

| 用例 | 断言 |
|---|---|
| 两端都合法 | 原样返回（步长为 0 时） |
| 步长非 0 | 两端按同一规则吸附（**对称性**） |
| 两端填反 | 自动交换 |
| 短于最小时长 | 结束时间往后推 |
| 起点贴着 24:00 且需补最小时长 | 改为把开始往前拉，仍满足 `end <= 1440` |
| 长于最长时长 | 从开始处截断 |
| 越界值 | 钳制到 `[0, 1440]` |

---

## 7. D3 与 FI-010 的接管语义

### 7.1 D3 不受影响

新增的 `EditDraft` / `EditResult` 是普通数据类，不影响 `EditSession.CancelResult` 的**无字段对象**保证。「点取消 → 类型上无法表达数据变更」这条结构性防线原封不动。

| 路径 | 是否发数据变更 |
|---|---|
| `applyEdit()` | 否——只改草稿 |
| `confirmEdit()` | **是**——唯一出口 |
| `cancelEdit()` | 否——只有 `onEditCancelled()` |

### 7.2 FI-010 在接管后的变化（需 PRD 同步）

PRD FI-010 规定「编辑态下点击外部区域视为取消」。但业务方接管后弹的是**组件外部**的表单：

- 若表单是模态（`BottomSheetDialog` 等），时间轴点不到，FI-010 不会触发，无冲突。
- 若表单是非模态（外部透明可点），用户点时间轴会触发 FI-010 → `cancelEdit()`，而表单还开着，随后提交时 session 已经死了。

**决定：`onEnterEditing` 返回 `true` 后，组件不再响应 FI-010。** 退出路径完全交给业务方。

实现：`DayTimelineView` 增加 `editTakenOver` 标志，在 `enterEditByLongPress` / `handleTap` 建会话后置位、在 `confirmEdit` / `cancelEdit` / `performDelete` 清零；`handleTap` 的 FI-010 分支先检查该标志。

这条改的是 PRD §8.2 的既有语义，已在 PRD v1.3 §7.7 与 §8.2 一并标注。

---

## 8. 影响面清单

### 8.1 新增

| 文件 | 内容 |
|---|---|
| `api/EditDraft.kt` | 组件 → 业务方快照 |
| `api/EditResult.kt` | 业务方 → 组件提交值 |
| `core/SnapCalculator.applyRange()` | 双边对称兜底（纯 Kotlin + 单测） |
| `DayTimelineView.applyEdit()` | 草稿写入口 |
| `EditController.onEnterEditing()` | 进入编辑态钩子 |

### 8.2 修改

| 文件 | 改动 |
|---|---|
| `DayTimelineView.EditController` | `onDone` / `onCancel` 签名变更；新增 `onEnterEditing` |
| `api/TimelineListener.kt` | `onEventCreated` / `onEventModified` 增加 `content` 参数（带默认值） |
| `core/TimelineEvent.kt` | 新增 `color: Int?`（带默认实现） |
| `core/EventSanitizer.kt` | `SanitizedEvent` 透传 `color` |
| `core/EditSession.kt` | 草稿持有待提交 `content`；`Commit` 携带它 |
| `api/Painters.kt` | `BlockContext` 新增 `accentColor: Int?` |
| `paint/DefaultPainters.kt` | 消费 `accentColor` |
| `docs/` × 2 | PRD v1.3、技术方案 AD-22 |

### 8.3 两个必须一起做的前提

1. **demo 必须真的弹表单**（`app/` 的 `MainActivity` + 新布局），走通「进入编辑态 → 预填 → 改 → 提交」。否则新增 API 全是死代码，违反 §12.4「死代码 0 处并纳入自动化阻断」。
2. **仪器测试必须覆盖新链路**。`applyEdit` / `onEnterEditing` / FI-010 接管语义都在 View 层，JVM 单测证不了。至少覆盖：接管后点外部不取消、`applyEdit` 的越界兜底、取消仍然零数据变更。

---

## 9. 未决问题

| 编号 | 问题 | 影响 | 建议 |
|---|---|---|---|
| QF-1 | 标题是否允许为空 | 空标题时块内显示什么、读屏读什么 | 允许为空，回落读屏占位文案（现有 `day_timeline_a11y_no_content` 已有） |
| QF-2 | 表单打开时组件是否仍可拖拽 | 影响「表单 + 拖拽」混合交互 | 保持可拖拽——拖拽只改草稿，表单提交时以 `applyEdit` 为准 |
| QF-3 | 冲突态（E28）下表单怎么办 | `hasConflict` 为 true 时是否还允许提交 | 允许提交，继续把 `hasConflict` 带给业务方，由业务方决定覆盖 |
| QF-4 | 颜色的深色适配责任 | 业务方需自备两套色 | 已在 §5.4 定为业务方责任，需在接入文档里写明 |

---

## 10. 关联文档

| 文档 | 关联点 |
|---|---|
| PRD v1.3 §7.7 | 编辑态规格新增「第四层接管表单」说明 |
| PRD v1.3 §8.2 | 交互状态流转补充接管后的 FI-010 语义 |
| PRD v1.3 §10.1 | 可配置项总览「编辑交互」补「编辑层是否可接管给业务方」 |
| PRD v1.3 §11.3 | 新建/修改事件表补「显示内容」 |
| 技术方案 AD-22 | 本方案的架构决策与实现约束 |
