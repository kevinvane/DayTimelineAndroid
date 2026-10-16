# DayTimeline 完整能力文档

> **这是组件能力的唯一真源**：全部配置项、全部回调、全部定制方式、数据契约。
> 接入步骤请看 [README](../README.md#快速开始)（5 分钟，不在此重复）。

- **依据**：PRD §7（界面与交互规格）、§8（交互规格）、§9（数据规格）、§10（分层定制）
- **对应实现**：`com.github.kevinvane.daytimeline.library`
- **一致性由测试守护**：`DocsConfigCoverageTest` 断言本文档列出的 XML 属性集合
  ≡ `attrs.xml` ≡ `TimelineConfig` 字段集合。**新增配置项而忘记写文档，测试立刻失败。**

> **为什么单独成文**：README 的目标是「5 分钟内跑起来」，塞进 55 项配置表会毁掉它；
> 而把全表留在 README 又会和源码 KDoc 形成两份需要同步的副本。
> 本文是那一份。

---

## 1. 两种配置方式

每个配置项都同时支持 XML 与代码，**一一对应、效果完全一致**。

```xml
<com.github.kevinvane.daytimeline.library.DayTimelineView
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:dtHourHeight="64dp"
    app:dtShowTimeSubtitle="true" />
```

```kotlin
timeline.setConfig(
    TimelineConfig(
        hourHeight = (64 * resources.displayMetrics.density).toInt(),
        showTimeSubtitle = true,
    ),
)
```

两条要点：

- `setConfig()` 采用**合并**语义：只覆盖你显式写出的字段，其余保持不变。
  可以放心「XML 打底 + 代码里再微调几项」。
- `TimelineConfig` 的所有字段都是**可空类型**，`null` 表示「用资源默认值」。
- **代码侧尺寸字段单位是 px**，需自行从 dp 换算；XML 侧由 AAPT2 处理。

### 1.1 两种滚动模式的高度约定

| | 自身滚动 `self`（默认） | 外部容器滚动 `external` |
|---|---|---|
| 高度 | 填满父容器（`match_parent`） | `wrap_content`，等于 `24 × 格高 + 上下留白` |
| 滚动由谁负责 | 组件自身 | 外层滚动容器 |
| 是否消费垂直手势 | 是 | 否，必须让给外层 |
| 旋转后滚动位置 | 组件自动恢复 | 由外层容器负责 |

> ⚠️ 外部滚动模式下高度配错会截断内容或留白过多。**组件不主动校验，请严格按上表设置。**

---

## 2. 全部配置项（55 项）

XML 属性名与代码字段名的对应关系固定为：属性名 = `dt` + 首字母大写的字段名。

### 2.1 布局尺寸（8 项）

| XML 属性 | 代码字段 | 默认值 |
|---|---|---|
| `dtHourHeight` | `hourHeight` | `56dp` |
| `dtAxisWidth` | `axisWidth` | `56dp` |
| `dtTopPadding` | `topPadding` | `8dp` |
| `dtBottomPadding` | `bottomPadding` | `8dp` |
| `dtEndMargin` | `endMargin` | `16dp` |
| `dtGridLineWidth` | `gridLineWidth` | `1dp` |
| `dtAxisLabelSize` | `axisLabelSize` | `12sp` |
| `dtNowLabelSize` | `nowLabelSize` | `12sp` |

`dtAxisLabelSize` 与 `dtNowLabelSize` 是**两个独立配置项**，可分别调整。

### 2.2 日程块外观（11 项）

| XML 属性 | 代码字段 | 默认值 |
|---|---|---|
| `dtBlockGap` | `blockGap` | `1dp` |
| `dtBlockCorner` | `blockCorner` | `4dp` |
| `dtBlockPaddingHorizontal` | `blockPaddingHorizontal` | `8dp` |
| `dtBlockPaddingVertical` | `blockPaddingVertical` | `4dp` |
| `dtBlockMinHeight` | `blockMinHeight` | `20dp` |
| `dtBlockAccentBarWidth` | `blockAccentBarWidth` | `4dp` |
| `dtBlockAccentBarMarginStart` | `blockAccentBarMarginStart` | `6dp` |
| `dtBlockAccentBarMarginVertical` | `blockAccentBarMarginVertical` | `6dp` |
| `dtBlockStrokeWidth` | `blockStrokeWidth` | `2dp` |
| `dtBlockTextSize` | `blockTextSize` | `12sp` |
| `dtShowTimeSubtitle` | `showTimeSubtitle` | `false` |

色条的内缩（`dtBlockAccentBarMarginStart` / `...Vertical`）**只作用于绘制**：
块的点击热区仍是整块（`minTouchTarget`），文字左边界同步让开「左内缩 + 色条宽」，
避免色条压到文字。

`dtBlockStrokeWidth` **默认不生效于常规态**——常规态不描边，描边色供自定义绘制
与业务方需要块边框时使用。

### 2.3 当前时间线（6 项）

| XML 属性 | 代码字段 | 默认值 |
|---|---|---|
| `dtShowNowIndicator` | `showNowIndicator` | `true` |
| `dtNowIndicatorTodayOnly` | `nowIndicatorTodayOnly` | `true` |
| `dtNowIndicatorSkipOverlappingHourLabel` | `nowIndicatorSkipOverlappingHourLabel` | `true` |
| `dtNowDotDiameter` | `nowDotDiameter` | `6dp` |
| `dtNowLineWidth` | `nowLineWidth` | `2dp` |
| `dtAutoLocateOnFirstShow` | `autoLocateOnFirstShow` | `true` |

### 2.4 时间行为（5 项）

| XML 属性 | 代码字段 | 默认值 | 可选值 |
|---|---|---|---|
| `dtTimeFormat` | `timeFormat` | `system` | `system` / `h24` / `h12` |
| `dtSnapMinutes` | `snapMinutes` | `15` | 吸附步长（分钟），`0` 关闭吸附但保留时长兜底 |
| `dtMinDurationMinutes` | `minDurationMinutes` | `5` | |
| `dtMaxDurationMinutes` | `maxDurationMinutes` | `1440` | |
| `dtDefaultNewDurationMinutes` | `defaultNewDurationMinutes` | `60` | 点空白新建时的默认时长 |

### 2.5 滚动行为（5 项）

| XML 属性 | 代码字段 | 默认值 | 说明 |
|---|---|---|---|
| `dtScrollMode` | `scrollMode` | `self` | `self` / `external`，见 §1.1 |
| `dtEdgeAutoScrollEnabled` | `edgeAutoScrollEnabled` | `true` | 拖到边缘时自动滚动 |
| `dtEdgeScrollTriggerSize` | `edgeScrollTriggerSize` | `48dp` | |
| `dtEdgeScrollStepSize` | `edgeScrollStepSize` | `8dp` | 每步位移 |
| `dtDragThresholdRatio` | `dragThresholdRatio` | `0.3` | 系统标准阈值的倍数 |

> `dtDragThresholdRatio` 是**无量纲比值**，刻意放在代码常量里而**不是** `dimens.xml`。
> 原因见 §6「资源契约」。

### 2.6 编辑交互（3 项）

| XML 属性 | 代码字段 | 默认值 |
|---|---|---|
| `dtEditHandleVisualSize` | `editHandleVisualSize` | `6dp` |
| `dtEditHandleTouchSize` | `editHandleTouchSize` | `48dp` |
| `dtMinTouchTarget` | `minTouchTarget` | `48dp` |

> **只应调大，不应调小**：触摸热区 ≥ 48dp 是无障碍硬要求，视觉可以更小。

> **组件不内置「完成 / 取消 / 删除」按钮**，因此上表是编辑交互的**全部**配置项。
> PRD v1.22 已把「可配置是否内置」这项需求**取消**——组件恒不内置，这是确定的行为，
> 不是「等组件以后加上」的缺口，也不存在对应的开关属性。
> 三个出口请通过 [§4 第四层定制](#4-四层定制) 用 `EditController` 接管。
>
> 这里刻意不写出「假如有的话该叫什么」：文档里出现一个不存在的属性名，
> 读者会以为配了却没生效——本仓库已经吃过一次这种亏（声明了却没有任何代码）。

### 2.7 颜色（17 项语义色项）

业务方换肤只需在自己的资源里**覆盖同名资源项**，勿改组件源码。

| XML 属性 | 代码字段 | 语义 | 浅色定稿 | 深色定稿 |
|---|---|---|---|---|
| `dtColorBackground` | `colorBackground` | 背景色 | `#FFFFFF` | `#101318` |
| `dtColorGridLine` | `colorGridLine` | 刻度线色 | `#E4E8EE` | `#343A44` |
| `dtColorAxisLabel` | `colorAxisLabel` | 轴标签文字色 | `#55606E` | `#D3DAE3` |
| `dtColorNow` | `colorNow` | 当前时间线色 | `#D92D20` | `#FF8A80` |
| `dtColorBlockBgPast` | `colorBlockBgPast` | 块底色·已过 | `#F1F4F7` | `#1B2027` |
| `dtColorBlockBgOngoing` | `colorBlockBgOngoing` | 块底色·进行中 | `#DCEBFF` | `#1E4F8F` |
| `dtColorBlockBgUpcoming` | `colorBlockBgUpcoming` | 块底色·未到 | `#EEF2F6` | `#262C35` |
| `dtColorBlockTextPast` | `colorBlockTextPast` | 块文字·已过 | `#949CA6` | `#8A929C` |
| `dtColorBlockText` | `colorBlockText` | 块文字·进行中/未到 | `#1D232B` | `#EDF1F5` |
| `dtColorBlockAccentPast` | `colorBlockAccentPast` | 色条·已过 | `#C4CBD4` | `#454C56` |
| `dtColorBlockAccent` | `colorBlockAccent` | 色条·进行中/未到 | `#2F6FED` | `#8AB8FF` |
| `dtColorBlockStroke` | `colorBlockStroke` | 块描边色 | `#E8ECF1` | `#454C56` |
| `dtColorSelected` | `colorSelected` | 选中态色 | `#1D5FD6` | `#A8CBFF` |
| `dtColorEditLayerBg` | `colorEditLayerBg` | 编辑层底色 | `#66FFFFFF` | `#66101318` |
| `dtColorEditLayerText` | `colorEditLayerText` | 编辑层文字色 | `#1B2129` | `#F2F5F8` |
| `dtColorEditLayerTime` | `colorEditLayerTime` | 编辑层时间文字色 | `#2A66E0` | `#A8CBFF` |
| `dtColorEditHandle` | `colorEditHandle` | 编辑手柄色 | `#2A66E0` | `#A8CBFF` |

三条约束：

1. **色值是设计交付物**，来源见 [设计侧定稿交付](DayTimeline-设计侧定稿交付.md)。
   就地调色等于绕开设计评审；改动会被 `verifyColorsFinalized` 门禁拦下。
2. **深浅色适配仅通过替换资源取值完成**，不允许编写分支逻辑。
   两个 `colors.xml` 必须声明完全相同的色名，**少写一个名字 = 运行期崩溃**。
3. `dtColorEditLayerBg` 的**透明度写进取值本身**（`66` = 40%），
   换肤时也要带上 alpha。

> **已知偏差**：浅色「已过态」文字 `#949CA6` 对其底色 `#F1F4F7` 为 **2.51:1**，低于「低对比度」
> 意图的下限。PRD §7.3 对该项只给定性要求而未给数值，故未判为不合格，
> 已交设计复核（建议值 `#868E98` = 3.00:1）。
> 紫色系那版为 2.63:1，改蓝色系后略微更差 —— 蓝色系整体偏冷偏暗，这一项跟着底色一起暗了。

> 业务**自定义色**（`TimelineEvent.color`）刻意**不并入**这 17 项：那 17 项靠
> `values/` + `values-night/` 适配深浅色，业务方覆盖会破坏这套机制。
> 深浅色适配由业务方自行按 `Configuration.uiMode` 处理，组件不做转换。

---

## 3. 数据契约

### 3.1 `TimelineEvent`

业务方数据与组件之间**唯一**的契约。接口而非基类，不侵入你已有的数据模型。

| 成员 | 类型 | 说明 |
|---|---|---|
| `id` | `String` | 唯一标识。重复时组件照常渲染并上抛数据异常，**不崩溃** |
| `start` / `end` | `MinuteOfDay` | 起止时刻。`end` 必须晚于 `start`，否则组件兜底修正 |
| `expiredOverride` | `Boolean?`（默认 `null`） | `true` 强制按「已过」配色；`false` 跳过「已过」判定按时间定 ongoing/upcoming；`null` 纯按时间推导 |
| `content` | `CharSequence?`（默认 `null`） | 显示内容，格式不限。`null` 时读屏用占位文案 |
| `color` | `Int?`（默认 `null`） | 业务色（ARGB）。`null` 用组件默认配色 |

### 3.2 `MinuteOfDay`

「一天中的第几分钟」。**私有构造函数的普通类**——刻意如此，且**不要**改成
`@JvmInline value class`：

```kotlin
MinuteOfDay.of(9, 30)       // 09:30
MinuteOfDay.ofMinute(570)   // 内部整数算法用
MinuteOfDay.parse("09:30")  // 非法格式返回 null，不抛异常

val (hour, minute) = t.hour to t.minute
t.plusMinutes(15)
t.isStrictlyAfter(other)    // 相接（e1 == s2）不算重叠
```

设成普通类而非 value class，是因为 value class 会让 Kotlin 把方法名改写成
`getStart-ZruiD9E()` 这类**非法 Java 标识符**，导致 Java 调用方根本无法实现
`TimelineEvent`。

> `of()` 与 `ofMinute()` **刻意不做钳制**：脏数据必须能原样进来，组件才能侦测到并上抛。
> 在构造时就钳制会让那两条检查变成永久死代码，业务方永远不知道自己传了越界数据。

### 3.3 Java 调用方

**Kotlin 与 Java 业务方均可实现 `TimelineEvent`。** Java 侧需自行声明
`expiredOverride` / `content` / `color` 三个可选方法的缺省值（返回 `null`），
完整示例见 `:r8test` 模块的 `JavaBusinessEvent.java`。

### 3.4 脏数据：容错 + 事件上抛

组件对不合规数据**一律容错修正，不崩溃，且修正对用户不可见**，
同时通过 `onDataIssues` 上抛：

| `DataIssue.Kind` | 触发 |
|---|---|
| `NON_POSITIVE_DURATION` | `end <= start` |
| `END_AFTER_MIDNIGHT` | `end` 晚于 24:00 |
| `START_BEFORE_MIDNIGHT` | `start` 早于 00:00 |
| `DUPLICATE_ID` | 标识重复 |
| `BLANK_ID` | 标识为空 |

`detailOf()` 返回的 `range` 已是修正后的值，**可直接显示**，业务方无需二次校验。

### 3.5 D3：取消绝不产生任何数据变更

> 这是本组件最硬的一条约定，已由 `:library` 与 `:r8test` 的仪器测试分别守住两侧。

- `cancelEdit()` 走的是 `EditSession.cancel()`，返回一个**无字段**的结果对象——
  在类型层面就**无法表达一次数据变更**。
- 取消路径唯一允许的回调是 `TimelineListener.onEditCancelled`，它不携带任何数据。
- 确认编辑只有 `confirmEdit()` 一个出口，不存在「先确认再回滚」的写法。

---

## 4. 四层定制

**你永远不需要修改或复制组件源码。**

| 层 | 入口 | 能做什么 |
|---|---|---|
| 1. 参数配置 | `TimelineConfig` / XML 属性 | 55 项尺寸、颜色、行为 |
| 2. 网格绘制 | `timeline.gridPainter` | 完全替换刻度线 / 轴标签 / 当前时间线的绘制 |
| 3. 块内容 | `timeline.eventBlockPainter` | 完全替换日程块内部的画什么、怎么画 |
| 4. 交互接管 | `timeline.editController` | 接管创建/编辑流程与删除二次确认 |

第 2、3 层是接口类型，**不能用 XML 声明**，只能在代码侧设置。

### 4.1 自定义绘制上下文

两个 Context 都是**复用对象**：组件只持有一个实例，每帧改写其字段。
**不要把它们存进字段长期持有**，只在 `paint()` 调用期间使用。

| | `GridContext` | `BlockContext` |
|---|---|---|
| 坐标单位 | px，已扣除轴宽 | px，视口坐标（已减去滚动偏移） |
| 关键字段 | `width` `height` `scrollOffset` `axisAreaEnd` `contentStart` `hourHeight` `nowMinute` `labelStep` | `event` `state` `left` `top` `width` `height` `selected` `accentColor` `startText` `endText` `showTimeSubtitle` `tooShortForText` |
| 便利方法 | `minuteToY(minute)` | — |

`BlockContext.accentColor` 来自 `TimelineEvent.color`；
`startText` / `endText` 是组件预格式化好的时间文本，可直接用作副标题与读屏描述，
无需自己处理 12/24 小时制。

---

## 5. 回调参考

### 5.1 `TimelineListener`（全部可选覆写）

| 回调 | 何时触发 |
|---|---|
| `onEventClick(event)` | 用户单击日程块 |
| `onEventLongClick(event)` | 用户长按日程块 |
| `onEventCreated(range, content)` | 用户在空白处新建并确认 |
| `onEventModified(event, range, content, hasConflict)` | 用户调整并确认 |
| `onEventDeleted(event)` | 用户删除并确认 |
| `onEditCancelled()` | 用户取消编辑。**不得据此改数据** |
| `onDataIssues(issues)` | 组件修正了脏数据 |
| `onNowRefreshed(nowMinute)` | 30 秒定时器或 `refreshNow()` 刷新了当前时间 |

**`content` 的三态语义必须区分**：

| 值 | 含义 |
|---|---|
| `null` | **未改标题**，沿用原值 |
| `""` | **清空标题** |
| 其他 | 设置为该值 |

`onEventModified` 的 `content` 是**修改后**的值，与 `event.content`（修改前）
对比即可知道标题是否变了。

`hasConflict == true` 表示业务方在编辑期间改了同一条日程。组件**保留了用户的编辑状态**，
是否覆盖由你决定。

> **组件不修改业务方数据、不做持久化、不发起任何网络请求。**
> 所有变更只通过事件通知，是否落库由你决定。

### 5.2 `DayTimelineView.EditController`（全部可选覆写）

| 回调 | 返回 `true` 的含义 |
|---|---|
| `onEnterEditing(draft): Boolean` | 接管。组件不再响应「点外部 = 取消」，退出路径交给你 |
| `onDone(draft, result): Boolean` | 已由你处理，组件不再自行发事件 |
| `onDelete(): Boolean` | 已由你处理删除 |
| `onCancel(draft)` | 无返回值，仅供你关闭自己的界面 |
| `confirmDelete(context, eventTitle, onConfirmed)` | 提供删除二次确认。有默认实现（`AlertDialog`），可覆写 |

`onDone` 的 `result` 是组件**实际生效**的值（已合法化），不是你传进去的原始输入。

> `onEnterEditing` 与 `enterEditModeAndNotify()` 要分清：
> 业务方详情弹窗点「删除」只需要编辑态、**不该**弹表单；点「编辑」才需要。
> 用 `enterEditMode()`（不通知接管）与 `enterEditModeAndNotify()`（通知接管）区分，
> 否则会出现「点编辑弹出两个表单」。

### 5.3 常用方法

| 场景 | 调用 |
|---|---|
| 按 id 取只读详情 | `detailOf(eventId)` |
| 按视口坐标取日程 | `eventAt(x, y)` |
| 切换日期 | `setViewDate(epochMillis)` |
| 指定「当前时间」（测试 / 特殊场景） | `setNowMinute(minute)` |
| 让时间线立刻跟上真实时间 | `refreshNow()` |
| 清除选中描边 | `clearSelection()` |
| 跳转指定时刻 | `scrollToMinute(minute, smooth = true)` |
| 读取 / 恢复滚动位置 | `getScrollOffset()` / `setScrollOffset(offset)` |
| 程序化进入编辑态 | `enterEditMode(id)` / `enterEditModeAndNotify(id)` |
| 灌值 / 提交 / 取消 | `applyEdit(result)` / `confirmEdit()` / `cancelEdit()` |
| 单条更新 / 删除 | `updateEvent(event)` / `removeEvent(id)` |

`detailOf` 与 `eventAt` 都是**纯读取**：不改变选中态、不进入编辑态、不触发任何回调。
`eventAt` 与用户手指点击走同一套命中规则（含 48dp 最小触摸目标扩展），
所以它的结论和用户实际点到的总是一致的。传入的坐标是**本视图的视口坐标**，
滚动偏移由组件内部处理，调用方不要自己加。

> `refreshNow()` 与 `setNowMinute(...)` 不是一回事：前者走与内置 30 秒定时器**相同**的路径
> （重算当前分钟、重算三态、重绘、发 `onNowRefreshed`）；后者会把「当前时间」钉死，定时器不再覆盖它。

---

## 6. 资源契约（接入方与贡献者都要看）

- **无量纲比值不要写进 `dimens.xml`。** 曾用 `<item format="float" type="dimen">`
  声明拖拽系数，AAPT2 按 `format` 编译成 `TYPE_FLOAT(0x4)`，而 `Resources.getDimension()`
  只接受 `TYPE_DIMENSION(0x5)`，真机上直接抛 `Resources$NotFoundException`。
  无量纲值请用代码常量，或 `<attr format="float">` + `TypedArray.getFloat()`。
- **`res.getDimension()` 返回的已经是 px**，不要再套一层 `TypedValue.applyDimension`，
  否则 density 被乘两次。
- **凡是用 `Paint` 绘制文字，都要显式设 `textSize`** —— 默认值是 12 **原始像素**不是 12sp。

> 这三条对**接入方**同样适用：你在自己的资源里覆盖尺寸时若引入无量纲 `dimen`，
> 会踩到同一个崩溃。

---

## 7. 无障碍

- 触摸热区 ≥ 48dp，视觉可更小（编辑手柄为「视觉 6dp + 热区 48×48dp」）
- 通过 `androidx.customview` 的虚拟视图为每个日程块与空网格暴露无障碍节点，
  读屏可获知内容、起止时间与「已过 / 进行中 / 未开始」状态
- 块高不足以显示块内要画的文字行数（显示时间副标题时为两行，否则一行）时自动降级，只读屏不显示文字

---

## 8. 兼容性

| 项 | 要求 |
|---|---|
| `minSdk` | **23**（Android 6.0） |
| `compileSdk` / `targetSdk` | 36 |
| JVM | 11（AGP 8.x 本身需要 **JDK 17** 运行） |
| UI 技术栈 | View 体系（AppCompat + Material 3 + ConstraintLayout）。**不是 Compose** |
| 主题 | 全部颜色走系统主题，深浅色均可用；布局方向支持 RTL |
| 第三方依赖 | **无** |
| 混淆 | 随 AAR 打包 `consumer-rules.pro`，**接入方开启混淆后无需任何配置** |

### 8.1 RTL 的接入前置条件（**必读，否则 RTL 静默失效**）

```xml
<application
    android:supportsRtl="true">
```

**不声明这一行，Android 框架会强制 LTR**：`View.setLayoutDirection(LAYOUT_DIRECTION_RTL)`
是空操作，组件的 RTL 镜像（时间轴区域移到右侧）永远不会被触发，**且没有任何报错或日志**。

清单属于接入方，不会随 AAR 合并，因此这一行只能由接入方声明。
组件自身无法检测接入方是否声明（读不到接入方的清单），也不会为此打印任何提示。

本仓库在 2026-10-13 补 RTL 测试时踩中此坑：镜像实现一直是对的，
但组件库自己的 androidTest 清单也没声明这一行，导致「支持 RTL」长期无人验证。
补上声明后 `CompatibilityMatrixTest.rtlMirrorsTheAxisToTheRightSide` 即转绿。

---

**相关文档**：[README](../README.md) ·
[产品需求文档](DayTimeline-产品与需求文档.md) ·
[技术方案与实施计划](DayTimeline-技术方案与实施计划.md) ·
[日程块表单输入方案](DayTimeline-日程块表单输入方案.md) ·
[设计侧定稿交付](DayTimeline-设计侧定稿交付.md) ·
[变更记录](../CHANGELOG.md)
