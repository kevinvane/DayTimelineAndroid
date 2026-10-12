# DayTimeline

[![Release](https://jitpack.io/v/kevinvane/DayTimelineAndroid.svg)](https://jitpack.io/#kevinvane/DayTimelineAndroid)
[![CI](https://github.com/kevinvane/DayTimelineAndroid/actions/workflows/ci.yml/badge.svg)](https://github.com/kevinvane/DayTimelineAndroid/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![minSdk](https://img.shields.io/badge/minSdk-23-green.svg)](https://developer.android.com/guide/topics/manifest/uses-sdk-element)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)

可嵌入任意 Android 应用的**单日 24 小时时间轴视图组件**。以「纵向时间 + 横向重叠分栏」呈现一天日程，
让用户直接在其上完成查看、新建、调整、删除。

- **纵向是时间**：00:00 → 24:00，每小时一格
- **横向是并列**：时间重叠的日程自动并排，不互相遮挡
- **可直接操作**：点 = 查看 / 长按 = 编辑 / 拖拽 = 调整
- **不碰你的数据**：无持久化、无网络、无数据变更副作用，所有变更只以事件通知
- **零第三方依赖**：只用 Android 平台 + AndroidX 官方库
- **单 View 全自绘**：网格区常驻子 View 数为 0

---

## 目录

- [版本与发布状态](#版本与发布状态)
- [兼容性](#兼容性)
- [快速开始](#快速开始)
- [数据契约](#数据契约)
- [配置](#配置)
- [四层定制](#四层定制)
- [回调参考](#回调参考)
- [混淆](#混淆)
- [无障碍](#无障碍)
- [已知限制](#已知限制)
- [示例应用](#示例应用)
- [从源码构建与质量门禁](#从源码构建与质量门禁)
- [参与贡献](#参与贡献)
- [文档](#文档)
- [许可](#许可)

---

## 版本与发布状态

本文档描述的是 **`master` 分支当前状态**。

| 已发布版本 | 坐标 | 对应提交 | 状态 |
|---|---|---|---|
| **`1.0.0-rc1`** | `com.github.kevinvane:DayTimelineAndroid:1.0.0-rc1` | `95d081c` | ✅ **可用**，已逐项核对 |

### 为什么是 `-rc1` 而不是 `1.0.0`

M6（发布）尚未完成：DL-03~DL-06 四份交付文档未齐。
`rc` 如实反映这一状态——**文档尚未齐备，不建议用于生产**。

> 色值已不再是阻塞项：§7.3 的 17 项语义色项已于 2026-10-12 换成
> [设计侧定稿](docs/DayTimeline-设计侧定稿交付.md)取值，R10 随之清零。

### 产物核对

`1.0.0-rc1` 的 AAR 已下载解包逐项核对（不只看 JitPack 的 `ok` 状态）：

- `EditDraft` / `EditResult` / `EventDetail` / `EventDetailKt` / `EditDraftKt` 五个类均在
- `detailOf` / `eventAt` / `enterEditMode` / `enterEditModeAndNotify` / `clearSelection` /
  `refreshNow` 六个方法均在
- `MinuteOfDay` 已无 `boxed-impl`（value class 标志），确为普通类，**Java 接入方可用**
- `proguard.txt` 含 21 条保留规则，与仓库 `consumer-rules.pro` 一致

功能实现进度：组件 M0–M5 已完成，M6（发布）进行中；**色值已于 2026-10-12 定稿**，
交互标注与切图/字体资源（M0-2 / M0-3）尚无交付，详见 [技术方案文档](docs/DayTimeline-技术方案与实施计划.md)。

---

## 兼容性

| 项 | 要求 |
|---|---|
| `minSdk` | **23**（Android 6.0） |
| `compileSdk` / `targetSdk` | 36 |
| JVM | 11（Android Gradle Plugin 8.x 本身需要 **JDK 17** 运行） |
| UI 技术栈 | View 体系（AppCompat + Material 3 + ConstraintLayout）。**不是 Compose** |
| 主题 | 全部颜色走系统主题，深浅色均可用；布局方向支持 RTL |
| 第三方依赖 | 无 |

---

## 快速开始

### 1. 引入依赖

`settings.gradle.kts`：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

模块 `build.gradle.kts`：

```kotlin
dependencies {
    implementation("com.github.kevinvane:DayTimelineAndroid:1.0.0-rc1")
}
```

> 详见[版本与发布状态](#版本与发布状态)。

### 2. 放置组件

```xml
<com.github.kevinvane.daytimeline.library.DayTimelineView
    android:id="@+id/timeline"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

组件有两种滚动模式，**高度约定不同**：

| | 自身滚动（默认） | 外部容器滚动 |
|---|---|---|
| 高度 | 填满父容器（`match_parent`） | `wrap_content`，等于 `24 × 格高 + 上下留白` |
| 滚动由谁负责 | 组件自身 | 外层滚动容器 |
| 是否消费垂直手势 | 是 | 否，必须让给外层 |
| 旋转后滚动位置 | 组件自动恢复（`onSaveInstanceState`） | 由外层容器负责 |

外部滚动模式的写法：

```xml
<com.github.kevinvane.daytimeline.library.DayTimelineView
    android:id="@+id/timeline"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:dtScrollMode="external" />
```

> ⚠️ 外部滚动模式下高度配错会截断内容或留白过多。**组件不主动校验，请严格按上表设置。**

### 3. 提供数据

业务方**在自己的数据类上实现 `TimelineEvent` 即可，不需继承任何基类**：

```kotlin
data class Meeting(
    val id: String,
    val beginAt: LocalTime,
    val endAt: LocalTime,
    val title: String,
) : TimelineEvent {
    override val start get() = MinuteOfDay.of(beginAt.hour, beginAt.minute)
    override val end get() = MinuteOfDay.of(endAt.hour, endAt.minute)
    override val content get() = title
}
```

`start` / `end` 的类型是 `MinuteOfDay`——**私有构造函数的普通类**，只能经 `MinuteOfDay.of(hour, minute)`
等具名工厂构造。业务方拿不到裸 `Int`，因此 `start = 90` 这类把「小时」误当「分钟」的代码**编译不过**。

### 4. 提交数据与监听事件

```kotlin
timeline.submitEvents(todayMeetings)

timeline.listener = object : TimelineListener {
    override fun onEventClick(event: TimelineEvent) { /* 弹自己的详情 */ }
    override fun onEventLongClick(event: TimelineEvent) { }

    override fun onEventCreated(range: IntRange, content: CharSequence?) { /* 自行落库 */ }

    override fun onEventModified(
        event: TimelineEvent,
        range: IntRange,
        content: CharSequence?,
        hasConflict: Boolean,
    ) { }

    override fun onEventDeleted(event: TimelineEvent) { }

    override fun onDataIssues(issues: List<DataIssue>) { /* 数据质量监控 */ }

    override fun onEditCancelled() { analytics.track("edit_cancel") }

    override fun onNowRefreshed(nowMinute: Int) { }
}
```

> **组件不修改业务方数据、不做持久化、不发起任何网络请求。**
> 所有变更只通过事件通知，是否落库由你决定。
>
> 特别地：**取消编辑绝不产生任何数据变更**。`onEditCancelled` 只用于统计编辑完成率，
> 切勿据此修改数据。详见[数据契约](#数据契约)中的 D3 条款。

### 5. 编辑态：接入你自己的表单

组件**不内置**「完成 / 取消 / 删除」按钮——它们属于你的界面，通过第四层定制接管：

```kotlin
timeline.editController = object : DayTimelineView.EditController {
    override fun onEnterEditing(draft: EditDraft): Boolean {
        showMyForm(prefill = draft)   // 用 draft 预填
        return true                   // 返回 true = 接管，退出路径交给我
    }
}

// 表单「确定」
timeline.applyEdit(EditResult(range = 540..600, content = "Review"))
timeline.confirmEdit()               // 唯一发出数据变更的出口

// 表单「取消」
timeline.cancelEdit()                // 结构上不可能带出数据变更
```

接管后组件不再响应「点组件外部区域 = 取消」（否则表单还没关、草稿就被取消了）。
退出路径完全交给你——**记得自己实现全部三个出口**。

### 6. 只读查询

```kotlin
// 按 id 取只读详情（已兜底修正后的值，可直接显示）
val detail = timeline.detailOf(eventId)

// 按视口坐标取日程，判断「这一点点到了哪条」
val hit = timeline.eventAt(x, y)
```

两者都是**纯读取**：不改变选中态、不进入编辑态、不触发任何回调。
`eventAt` 与用户手指点击走同一套命中规则（含 48dp 最小触摸目标扩展），
所以它的结论和用户实际点到的总是一致的。传入的坐标是**本视图的视口坐标**，
滚动偏移由组件内部处理，调用方不要自己加。

### 7. 其余常用入口

| 场景 | 调用 |
|---|---|
| 切换日期 | `setViewDate(epochMillis)` |
| 指定「当前时间」（测试 / 特殊场景） | `setNowMinute(minute)` |
| 让时间线立刻跟上真实时间 | `refreshNow()` |
| 清除选中描边 | `clearSelection()` |
| 跳转指定时刻 | `scrollToMinute(minute, smooth = true)` |
| 读取 / 恢复滚动位置 | `getScrollOffset()` / `setScrollOffset(offset)` |
| 程序化进入编辑态 | `enterEditMode(id)` / `enterEditModeAndNotify(id)` |
| 单条更新 / 删除 | `updateEvent(event)` / `removeEvent(id)` |

> `refreshNow()` 与 `setNowMinute(...)` 不是一回事：前者走与内置 30 秒定时器**相同**的路径
> （重算当前分钟、重算三态、重绘、发 `onNowRefreshed`）；后者会把「当前时间」钉死，定时器不再覆盖它。

---

## 数据契约

### `TimelineEvent`

业务方数据与组件之间**唯一**的契约。接口而非基类，不侵入你已有的数据模型。

| 成员 | 类型 | 说明 |
|---|---|---|
| `id` | `String` | 唯一标识。重复时组件照常渲染并上抛数据异常，**不崩溃** |
| `start` / `end` | `MinuteOfDay` | 起止时刻。`end` 必须晚于 `start`，否则组件兜底修正 |
| `expiredOverride` | `Boolean?`（默认 `null`） | `true` 强制按「已过」配色；`false` 跳过「已过」判定按时间定 ongoing/upcoming；`null` 纯按时间推导 |
| `content` | `CharSequence?`（默认 `null`） | 显示内容，格式不限。`null` 时读屏用占位文案 |
| `color` | `Int?`（默认 `null`） | 业务色（ARGB）。`null` 用组件默认配色 |

> `color` 刻意**不并入** 17 项语义色项：那 17 项靠 `values/` + `values-night/` 适配深浅色，
> 业务方覆盖会破坏这套机制。深浅色适配由**业务方**自行按 `Configuration.uiMode` 处理，组件不做转换。

### `MinuteOfDay`

「一天中的第几分钟」。**私有构造函数的普通类**——刻意如此，且**不要**改成 `@JvmInline value class`：

```kotlin
MinuteOfDay.of(9, 30)       // 09:30
MinuteOfDay.ofMinute(570)   // 内部整数算法用
MinuteOfDay.parse("09:30")  // 非法格式返回 null，不抛异常

val (hour, minute) = t.hour to t.minute
t.plusMinutes(15)
t.isStrictlyAfter(other)    // 相接（e1 == s2）不算重叠
```

设成普通类而非 value class 是因为 value class 会让 Kotlin 把方法名改写成
`getStart-ZruiD9E()` 这类**非法 Java 标识符**，导致 Java 调用方根本无法实现 `TimelineEvent`。

> `of()` 与 `ofMinute()` **刻意不做钳制**：脏数据必须能原样进来，组件才能侦测到并上抛。
> 在构造时就钳制会让那两条检查变成永久死代码，业务方永远不知道自己传了越界数据。

### Java 调用方

**Kotlin 与 Java 业务方均可实现 `TimelineEvent`。** Java 侧需自行声明 `expiredOverride` /
`content` / `color` 三个可选方法的缺省值（返回 `null`），完整示例见 `:r8test` 模块的
`JavaBusinessEvent.java`。

### 脏数据：容错 + 事件上抛

组件对不合规数据**一律容错修正，不崩溃，且修正对用户不可见**，同时通过 `onDataIssues` 上抛：

| `DataIssue.Kind` | 触发 |
|---|---|
| `NON_POSITIVE_DURATION` | `end <= start` |
| `END_AFTER_MIDNIGHT` | `end` 晚于 24:00 |
| `START_BEFORE_MIDNIGHT` | `start` 早于 00:00 |
| `DUPLICATE_ID` | 标识重复 |
| `BLANK_ID` | 标识为空 |

`detailOf()` 返回的 `range` 已是修正后的值，**可直接显示**，业务方无需二次校验。

### D3：取消绝不产生数据变更

> 这是本组件最硬的一条约定，已由 `:library` 与 `:r8test` 的仪器测试分别守住两侧。

- `cancelEdit()` 走的是 `EditSession.cancel()`，返回一个**无字段**的结果对象——
  在类型层面就**无法表达一次数据变更**。
- 取消路径唯一允许的回调是 `TimelineListener.onEditCancelled`，它不携带任何数据。
- 确认编辑只有 `confirmEdit()` 一个出口，不存在「先确认再回滚」的写法。

---

## 配置

每个配置项都支持 **XML** 与 **代码**两种方式，二者**一一对应、效果完全一致**。

```xml
<com.github.kevinvane.daytimeline.library.DayTimelineView
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:dtHourHeight="64dp"
    app:dtShowTimeSubtitle="true"
    app:dtSnapMinutes="5"
    app:dtTimeFormat="h24"
    app:dtScrollMode="self" />
```

```kotlin
// 代码侧的尺寸字段单位是 **px**，需要自己从 dp 换算
timeline.setConfig(
    TimelineConfig(
        hourHeight = (64 * resources.displayMetrics.density).toInt(),
        showTimeSubtitle = true,
    ),
)
```

`setConfig()` 采用**合并**语义：只覆盖你显式写出的字段，其余保持不变。
因此可以放心「XML 打底 + 代码里再微调几项」。

> `TimelineConfig` 的所有字段都是可空类型，`null` 表示「用资源默认值」。

### 全部配置项

`TimelineConfig` 共 **55** 个字段，与 XML 属性一一对应。

**布局尺寸**

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

**日程块外观**

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

**当前时间线**

| XML 属性 | 代码字段 | 默认值 |
|---|---|---|
| `dtShowNowIndicator` | `showNowIndicator` | `true` |
| `dtNowIndicatorTodayOnly` | `nowIndicatorTodayOnly` | `true` |
| `dtNowIndicatorSkipOverlappingHourLabel` | `nowIndicatorSkipOverlappingHourLabel` | `true` |
| `dtNowDotDiameter` | `nowDotDiameter` | `6dp` |
| `dtNowLineWidth` | `nowLineWidth` | `2dp` |
| `dtAutoLocateOnFirstShow` | `autoLocateOnFirstShow` | `true` |

**时间行为**

| XML 属性 | 代码字段 | 默认值 |
|---|---|---|
| `dtTimeFormat` | `timeFormat` | `system`（可选 `h24` / `h12`） |
| `dtSnapMinutes` | `snapMinutes` | `15`（`0` 关闭吸附，仍保留时长兜底） |
| `dtMinDurationMinutes` | `minDurationMinutes` | `5` |
| `dtMaxDurationMinutes` | `maxDurationMinutes` | `1440` |
| `dtDefaultNewDurationMinutes` | `defaultNewDurationMinutes` | `60` |

**滚动行为**

| XML 属性 | 代码字段 | 默认值 |
|---|---|---|
| `dtScrollMode` | `scrollMode` | `self`（可选 `external`） |
| `dtEdgeAutoScrollEnabled` | `edgeAutoScrollEnabled` | `true` |
| `dtEdgeScrollTriggerSize` | `edgeScrollTriggerSize` | `48dp` |
| `dtEdgeScrollStepSize` | `edgeScrollStepSize` | `8dp` |
| `dtDragThresholdRatio` | `dragThresholdRatio` | `0.3`（代码常量，不是资源） |

**编辑交互**

| XML 属性 | 代码字段 | 默认值 |
|---|---|---|
| `dtEditHandleVisualSize` | `editHandleVisualSize` | `6dp` |
| `dtEditHandleTouchSize` | `editHandleTouchSize` | `48dp`（**只应调大，不应调小**） |
| `dtMinTouchTarget` | `minTouchTarget` | `48dp` |

**颜色（17 项语义色项）**

`dtColorBackground`、`dtColorGridLine`、`dtColorAxisLabel`、`dtColorNow`、
`dtColorBlockBgPast` / `Ongoing` / `Upcoming`、`dtColorBlockTextPast` / `Text`、
`dtColorBlockAccentPast` / `Accent`、`dtColorBlockStroke`、`dtColorSelected`、
`dtColorEditLayerBg` / `Text` / `Time`、`dtColorEditHandle`
—— 对应 `colorBackground` … `colorEditHandle`。

默认值取自 `values/colors.xml` 与 `values-night/colors.xml`，深浅色各自一套。
**业务方换肤只需在自己的资源里覆盖同名项，勿改组件源码。**

> `dragThresholdRatio` 是无量纲比值，按无障碍规范刻意放在代码常量里而**不是** `dimens.xml`。
> 见下文《从源码构建与质量门禁》里的资源契约说明。

---

## 四层定制

**你永远不需要修改或复制组件源码。**

| 层 | 入口 | 能做什么 |
|---|---|---|
| 1. 参数配置 | `TimelineConfig` / `dtXxx` | 55 项尺寸、颜色、行为 |
| 2. 网格绘制 | `timeline.gridPainter` | 完全替换刻度线 / 轴标签 / 当前时间线的绘制 |
| 3. 块内容 | `timeline.eventBlockPainter` | 完全替换日程块内部的画什么、怎么画 |
| 4. 交互接管 | `timeline.editController` | 接管创建/编辑流程与删除二次确认 |

```kotlin
// 第三层：完全替换日程块内容
timeline.eventBlockPainter = object : EventBlockPainter {
    override fun paint(
        canvas: Canvas,
        ctx: BlockContext,
        colors: TimelineColors,
        paints: Paints,
    ) {
        // 坐标系与 RTL 镜像已由组件处理好，只管画
    }
}
```

### 自定义绘制上下文

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

## 回调参考

### `TimelineListener`（全部可选覆写）

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

`content` 的三态语义必须区分：

| 值 | 含义 |
|---|---|
| `null` | **未改标题**，沿用原值 |
| `""` | **清空标题** |
| 其他 | 设置为该值 |

`onEventModified` 的 `content` 是**修改后**的值，与 `event.content`（修改前）对比即可知道标题是否变了。

`hasConflict == true` 表示业务方在编辑期间改了同一条日程。组件**保留了用户的编辑状态**，
是否覆盖由你决定。

### `DayTimelineView.EditController`（全部可选覆写）

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

---

## 混淆

组件以 AAR 形式发布，业务方开启混淆后功能必须正常。
`consumer-rules.pro` 随 AAR 打包，**对接入方自动生效，无需任何配置**。

保留规则已由 `:r8test` 模块以**业务方身份**验证：以开启 R8 的 release 变体构建，
核对 10 个对外契约类符号，并在**混淆后的**变体上跑仪器测试。

> library 自身不开混淆，保留规则写错也不会有任何东西报错——
> 所以这条验证不是走过场，`NoSuchMethodError` 与 `NoClassDefFoundError` 都是它实际抓到的缺陷。

---

## 无障碍

- 触摸热区 ≥ 48dp，视觉可更小（编辑手柄为「视觉 6dp + 热区 48×48dp」）
- 通过 `androidx.customview` 的虚拟视图为每个日程块与空网格暴露无障碍节点，
  读屏可获知内容、起止时间与「已过 / 进行中 / 未开始」状态
- 块高不足以显示两行文字时自动降级，只读屏不显示文字

---

## 已知限制

| 限制 | 说明 |
|---|---|
| 仅单日 | 不支持跨天。跨天由业务方在更高层实现 |
| 最小粒度为分钟 | 不支持秒 |
| 不处理重复日程规则 | 业务方自行展开 |
| 不做数据持久化 | 组件不落库、不联网 |
| 不内置编辑按钮 | 「完成 / 取消 / 删除」需业务方通过 `EditController` 接管实现 |
| 不提供图形化配置界面 | 配置只经 XML / 代码 |
| 同刻日程等宽并排 | 总列数取当天最大重叠数，不按内容长度做流式宽度 |
| **文案暂仅有中文** | 组件内文案全部走 `strings.xml`（可被业务方覆盖），但库自身**未提供 `values-xx` 翻译**。非中文环境会显示中文，请在自己的资源里覆盖同名项 |
| **交付文档未齐** | DL-03~DL-06（快速上手 / 完整能力 / 主题定制 / 交互说明）尚未产出，当前以本文档与 KDoc 为准 |

---

## 示例应用

`:app` 模块是一个只做一件事的 demo：把组件接起来，跑通完整闭环。

- XML 配置尺寸与行为 + 代码补充一项，验证两条配置路径可叠加
- 接管 `EditController`，弹出**自己实现的**表单（`BottomSheetDialog`）
- 单击弹出**自己实现的**只读详情弹窗（`PopupWindow`）
- 删除二次确认、表单取消、详情弹窗取消的全部收尾路径
- 演示「备注」等组件不关心的业务字段只存在于业务方数据类里

```powershell
.\gradlew.bat :app:installDebug
```

`MainActivityCancelPathTest`（7 条）专门守住 demo 侧的取消链路——
组件测试全绿但业务方收尾代码漏了的情况，在真实项目里发生过不止一次。

---

## 从源码构建与质量门禁

```powershell
# 确认工程可配置
.\gradlew.bat projects

# 编译
.\gradlew.bat :library:assembleDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug

# JVM 单元测试（不需要设备）
.\gradlew.bat :library:testDebugUnitTest

# 门禁（全部可离线执行，不需要设备）
.\gradlew.bat detekt                        # 静态检查 + 代码风格 0 违规
.\gradlew.bat :library:verifyNoDeadCode     # 死代码 0 处
.\gradlew.bat :library:verifyCoreCoverage   # 核心逻辑覆盖率 ≥ 90%
.\gradlew.bat :library:verifyAllCoverage    # 可测逻辑覆盖率 ≥ 75%
.\gradlew.bat :library:lintDebug            # 平台规范检查 0 错误 0 警告

# 需要设备 / 模拟器
.\gradlew.bat :library:connectedDebugAndroidTest
.\gradlew.bat :r8test:verifyKeptSymbols         # 混淆产物 + 对外契约类符号核对
.\gradlew.bat :r8test:connectedReleaseAndroidTest
```

> ⚠️ **JitPack 的构建绿灯不是门禁。** 它执行的是
> `clean publishToMavenLocal -xtest -xlint`——**跳过测试与 lint，也不触发上面任何一个
> `verify*` 任务**。上面这张清单只由 [CI](.github/workflows/ci.yml) 把关。
> 发版前请自己跑一遍，或看 CI 结果。
>
> 另：仓库未应用 `maven-publish`、也没有 `jitpack.yml`，JitPack 会自行识别 Android library
> 模块并注入 `maven-publish`、自动选用 JDK 17（**消费者不受影响**），代价是本地
> 跑不了 `publishToMavenLocal`。

### 工程结构

```
library/src/main/java/.../library/
├── DayTimelineView.kt    # 对外唯一入口
├── api/                  # 对外契约：配置、事件、定制扩展点
├── core/                 # 纯 Kotlin，零 android.* 依赖 —— 覆盖率 ≥ 90% 的主体
├── paint/                # 绘制实现、17 项语义色项、无障碍虚拟视图
└── internal/             # 尺寸解析、状态保存
app/                      # demo 与验收载体，不含可复用逻辑
r8test/                   # 混淆消费端验证：以业务方身份开 R8 跑仪器测试
```

`core/` **禁止 import 任何 `android.*`**。这条纪律是覆盖率能达标的前提——纯 Kotlin 类可用
JVM 单测直接跑，不需要设备也不需要 Robolectric。

### 门禁现状

| 门禁 | 状态 | 说明 |
|---|---|---|
| 核心逻辑覆盖率 ≥ 90% | ✅ 达标 | **91.81%**（2466/2686） |
| 可测逻辑覆盖率 ≥ 75% | ✅ 达标 | **88.60%**（3310/3736） |
| 平台规范检查 0 错误 0 警告 | ✅ 达标 | `warningsAsErrors = true`，无 lint baseline |
| 代码风格 / 静态检查 | ✅ 达标 | detekt 0 违规 |
| 死代码 0 处 | ✅ 达标 | `:library:verifyNoDeadCode` 自动阻断 |
| JVM 单元测试 | ✅ 达标 | **144** 个用例全绿 |
| 仪器测试 | ✅ 达标 | `:library` **102**、`:app` **7**、`:r8test` 混淆变体 **8** |
| R8 混淆消费端验证 | ✅ 达标 | 10 个对外契约类符号核对 + 混淆变体仪器测试 8/8 |
| 已知安全漏洞 / 性能基准 / 文档完整性 | ⚠️ 未自动化 | 无 CI 或 Gradle 落地，靠人工核对 |

> **覆盖率口径**：`paint` / `internal` / 根包（含 View 绘制与无障碍）在结构上拿不到
> JVM 单测覆盖率，共 6519 条指令（约占总量的 59%），由 102 条仪器测试与真机走查覆盖。
> 因此 `verifyAllCoverage` 的统计范围是「**JVM 可测逻辑**」，不是全部代码。

> **编译通过不等于能运行。** 曾出现过 lint 干净、107 个单测全绿、覆盖率达标，
> 但真机启动即崩的情况：`dimens.xml` 里用 `<item format="float" type="dimen">` 声明
> 无量纲比值，AAPT2 按 `format` 编译成 `TYPE_FLOAT`，而 `Resources.getDimension()`
> 只接受 `TYPE_DIMENSION`。此类问题对 JVM 单测与 lint 完全不可见，
> 因此仓库有专门的 `ResourceContractTest` 仪器测试守住资源契约。

### 环境

- JDK 17，Gradle 8.13，AGP 8.13.2，Kotlin 2.0.21
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` **任选其一**设置；否则需在仓库根目录建
  `local.properties` 并写入 `sdk.dir=D:\Android\Sdk`（该文件被 gitignore）
- `compileSdk 36` 需要本机装有 `android-36` 平台

---

## 参与贡献

欢迎 Issue 与 PR。

**提 Issue 前请先读** [产品需求文档](docs/DayTimeline-产品与需求文档.md)：
组件的交互与数据契约几乎每一处都有对应的需求编号（如 `FI-004`、`D3`、`D11`），
带上这些编号能让问题定位快得多。

**行为变更、配置项增减、公开 API 改动，请先开 Issue 讨论**——
这些会影响所有接入方。

开发者请先读 [AGENTS.md](AGENTS.md)，里面记录了本仓库的硬性约束与已踩过的坑。

---

## 文档

| 文档 | 内容 |
|---|---|
| [产品需求文档](docs/DayTimeline-产品与需求文档.md) | 需求、界面与交互规格、数据契约、质量要求、验收标准。**唯一需求来源** |
| [技术方案与实施计划](docs/DayTimeline-技术方案与实施计划.md) | 架构决策 AD-01~AD-28、任务拆解、门禁落地方案、开放问题、实施进度 |
| [日程块表单输入方案](docs/DayTimeline-日程块表单输入方案.md) | 表单输入能力设计（AD-22） |
| [AGENTS.md](AGENTS.md) | 面向 AI 协作者的仓库指引与硬性约束 |

三份文档均为 UTF-8 中文。PowerShell 控制台需先设
`[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` 才能正常显示。

---

## 许可

本项目采用 [Apache License 2.0](LICENSE)，许可证全文见仓库根目录的 [`LICENSE`](LICENSE) 文件。
