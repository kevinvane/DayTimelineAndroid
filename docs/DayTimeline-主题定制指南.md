# DayTimeline 主题定制指南

> **如何换肤、如何替换日程块样式**（PRD §18 DL-05 / §10.2）。
> 接入步骤见 [README](../README.md)，配置项全表见[完整能力文档](DayTimeline-完整能力文档.md)。

- **核心原则**：**你永远不需要修改或复制组件源码。** 色项名称是对外契约。
- **示例色值**：本文所有截图与示例均使用**设计定稿值**（当前为品牌蓝版本），
  不使用任何占位色。

---

## 0. 先选通道

四层定制，按「改动量」从小到大：

| 你想做的事 | 走哪条路 | 要写多少代码 |
|---|---|---|
| 换个主题色（品牌蓝 → 品牌绿） | **通道 A**：覆盖同名资源 | 34 个十六进制值，零逻辑 |
| 只改某几项，或运行时动态换肤 | **通道 B**：`TimelineConfig` 颜色字段 / `dtColorXxx` | 几行 |
| 改日程块**长什么样**（形状、布局、加进度条） | **通道 C**：`eventBlockPainter` | 一个 `paint()` |
| 改网格/时间轴的绘制 | **通道 D**：`gridPainter` | 一个 `paint()` |
| 单条日程用自己的颜色 | **通道 E**：`TimelineEvent.color` | 一个字段 |

> 通道 A 与 E 是**两条独立的颜色通道**，混用会互相打脸，见 §2.3。

---

## 1. 通道 A：覆盖同名资源（推荐）

组件的颜色全部来自 `@color/day_timeline_*`，
而 Android 的资源合并规则是**应用资源覆盖库资源**。
所以换肤只要在你的 app 模块里定义**同名**资源：

```xml
<!-- app/src/main/res/values/colors.xml -->
<resources>
    <!-- 品牌绿版本 -->
    <color name="day_timeline_block_accent">#1B8A5A</color>
    <color name="day_timeline_selected">#0F6B45</color>
    <!-- ……其余按需覆盖…… -->
</resources>
```

```xml
<!-- app/src/main/res/values-night/colors.xml —— 深色必须单独覆盖一份 -->
<resources>
    <color name="day_timeline_block_accent">#5FD3A0</color>
    <color name="day_timeline_selected">#8AE7BE</color>
</resources>
```

### 1.1 三条必须遵守的规则

1. **深浅两套都要覆盖。** 组件的深色适配只通过替换资源取值完成、
   不允许代码分支（PRD §7.3 ②）。你只覆盖浅色的话，深色模式会继续用库的蓝色。
2. **两个 `colors.xml` 的色名必须完全一致。** 组件要求两套名称对齐，
   少写一个不会在编译期报错，而是**运行期找不到资源直接崩溃**。
3. **不要改组件源码、不要改库的资源文件。** 色项名称是对外契约，
   改名会让所有已接入的覆盖失效。

### 1.2 尺寸也能同样覆盖

同理，`@dimen/day_timeline_*` 也可以同名覆盖：

```xml
<dimen name="day_timeline_hour_height">72dp</dimen>
<dimen name="day_timeline_axis_width">64dp</dimen>
```

> ⚠️ **不要引入无量纲的 `dimen`。** 比如想把吸附系数写进资源时，
> 千万别用 `<item format="float" type="dimen">` ——
> AAPT2 会按 `format` 编译成 `TYPE_FLOAT(0x4)`，而 `Resources.getDimension()`
> 只接受 `TYPE_DIMENSION(0x5)`，真机上直接抛 `Resources$NotFoundException`。
> 组件自己就踩过这个坑（见技术方案 AD-15）。无量纲值请用代码常量，
> 或 `<attr format="float">` + `TypedArray.getFloat()`。

---

## 2. 通道 B：`TimelineConfig` / `dtColorXxx`

适合「只改几项」或「运行时动态换肤」。

```xml
<com.github.kevinvane.daytimeline.library.DayTimelineView
    app:dtColorBackground="@color/my_surface"
    app:dtColorBlockAccent="?attr/colorPrimary" />
```

```kotlin
timeline.setConfig(
    TimelineConfig(
        colorBackground = mySurfaceColor,
        colorBlockAccent = primaryColor,
    ),
)
```

`setConfig()` 是**合并**语义：只覆盖你写出的字段，其余保持不变。

### 2.1 动态换肤

`TimelineConfig` 可以在运行时反复调用，**不需要重建视图**：

```kotlin
fun applyTheme(dark: Boolean) {
    timeline.setConfig(
        TimelineConfig(
            colorBackground = if (dark) myDarkSurface else myLightSurface,
            colorBlockAccent = if (dark) myDarkAccent else myLightAccent,
            // ……
        ),
    )
}
```

### 2.2 自定义绘制时怎么拿到颜色

替换绘制器时，17 项语义色会以 `TimelineColors` 传进来，
**已经是解析好的 `@ColorInt`，不用自己查资源**：

```kotlin
timeline.eventBlockPainter = object : EventBlockPainter {
    override fun paint(
        canvas: Canvas,
        context: BlockContext,
        colors: TimelineColors,
        defaultPaints: Paints,
    ) {
        // colors.blockBgOngoing 就是「进行中」块底色，已按当前主题解析好
        canvas.drawColor(colors.blockBgOngoing)
    }
}
```

`TimelineColors` 共 17 个属性，与 §7.3 的语义色项一一对应：
`background` `gridLine` `axisLabel` `now` `blockBgPast` `blockBgOngoing`
`blockBgUpcoming` `blockTextPast` `blockText` `blockAccentPast` `blockAccent`
`blockStroke` `selected` `editLayerBg` `editLayerText` `editLayerTime` `editHandle`。

### 2.3 通道 A/E 与 17 项色项是三件独立的事

这是最常被误解的一点：

| | 走什么机制 | 深浅色适配责任在谁 |
|---|---|---|
| **17 项语义色项**（通道 A/B） | `@color/day_timeline_*` 资源 | 资源（`values/` + `values-night/`） |
| **业务色** `TimelineEvent.color` | 每条日程携带的 ARGB | **业务方** |
| **`TimelineEvent.color` 不并入那 17 项** | —— | —— |

**为什么刻意分开**：那 17 项靠 `values/` + `values-night/` 自动适配深浅色。
如果允许业务色走同一套机制，业务方覆盖资源就会破坏深色适配。
所以业务色是纯值，深浅两套要**你自己**按 `Configuration.uiMode` 准备。

```kotlin
// 业务色：深浅两套自己管
override val color: Int? = when (uiMode) {
    Configuration.UI_MODE_NIGHT_YES -> nightAccent
    else -> dayAccent
}
```

---

## 3. 通道 C：替换日程块样式

当你要改的**不是颜色而是形状与布局**（色条改成竖线、加进度条、改圆角、改文字排版），
用 `eventBlockPainter` 完全接管块内绘制：

```kotlin
timeline.eventBlockPainter = object : EventBlockPainter {
    override fun paint(
        canvas: Canvas,
        context: BlockContext,
        colors: TimelineColors,
        defaultPaints: Paints,
    ) {
        // 坐标系与 RTL 镜像已由组件处理好，只管画
        val paint = defaultPaints.background
        paint.color = colors.blockBgOngoing
        canvas.drawRoundRect(
            context.left, context.top,
            context.left + context.width, context.top + context.height,
            8f, 8f, paint,
        )
    }
}
```

> `Paints` 只有四支：`background` / `text` / `accent` / `stroke`。
> 它们**已经按配置设好 `textSize` 与 `typeface`**，可以直接用；要换字族见 §3.3。

### 3.1 `BlockContext` 常用字段

| 字段 | 含义 |
|---|---|
| `event` | 当前这条日程（含 `id` / `content` / `color`） |
| `state` | `PAST` / `ONGOING` / `UPCOMING` |
| `left` `top` `width` `height` | 块的位置与尺寸（视口坐标 px） |
| `selected` | 是否处于选中态 |
| `accentColor` | 左侧色条颜色（来自 `TimelineEvent.color`，为 `null` 时用组件默认） |
| `startText` `endText` | **组件已格式化好**的时间文本，可直接用作副标题与读屏描述 |
| `showTimeSubtitle` | 是否应展示时间副标题 |
| `tooShortForText` | 块太矮放不下两行文字 |

### 3.2 三个容易踩的坑

**① 两个 Context 都是复用对象。**
组件只持有一个实例，每帧改写其字段。
**不要把它们存进字段长期持有** —— 下一次绘制时内容就变了。只在 `paint()` 期间使用。

**② 坐标系已经处理好了。** 视口坐标（已减去滚动偏移）、
RTL 镜像、色条内缩都已由组件算好，直接用 `context.left` 等字段即可。
自己再减一次滚动偏移是最常见的错误。

**③ 触摸热区与绘制是两回事。**
`context.left/top/width/height` 是**视觉**范围。
块的点击热区始终是整块（`minTouchTarget`，默认 48dp），
色条内缩等调整只影响绘制。你若大幅改变块形状，
热区不会跟着变——这是刻意的（PRD UF-001）。

### 3.3 想改文字排版

`defaultPaints` 里带了几支已按配置设好 `textSize` 与 `typeface` 的笔
（组件自 M0-3 起默认用 `sans-serif` 作正文、`sans-serif-medium` 作当前时间线与选中态标题）。
要换字族，覆盖对应 `paint.typeface` 即可：

```kotlin
paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
paint.textSize = 14f * resources.displayMetrics.scaledDensity
```

> ⚠️ 用 `Paint` 画文字时**务必显式设 `textSize`** —— 默认值是 12 **原始像素**不是 12sp。
> 组件自己踩过这个坑（技术方案 AD-19）。

---

## 4. 通道 D：替换网格绘制

刻度线、轴标签、当前时间线都在这里：

```kotlin
timeline.gridPainter = object : GridPainter {
    override fun paint(
        canvas: Canvas,
        context: GridContext,
        colors: TimelineColors,
        defaultPaints: Paints,
    ) {
        context.minuteToY(9 * 60) // 09:00 的 y 坐标，组件已帮你换算
    }
}
```

`GridContext` 的坐标单位是**内容坐标**（已扣除轴宽）。完整字段：

| 类别 | 字段 |
|---|---|
| 视口 | `width` `height` `scrollOffset` |
| 轴与内容区 | `axisAreaEnd` `contentStart` `contentEnd` |
| 布局 | `hourHeight` `topPadding` `bottomPadding` `gridLineWidth` |
| 当前时间线 | `showNowIndicator` `nowMinute` `nowDotDiameter` `nowLineWidth` `skipOverlappingHourLabel` |
| 标签 | `labelStep` |
| 便利方法 | `minuteToY(minute)`、`blockBackground(state)`、`blockText(state)`、`blockAccentColor(state)` |

后三个便利方法按三态（`PAST` / `ONGOING` / `UPCOMING`）取对应颜色，
自己判断 `EventState` 再查表也行，但用它们更不容易漏。

> 为 `null` 时使用组件默认实现（`DefaultGridPainter` / `DefaultEventBlockPainter`），
> 不需要自己重写现有逻辑时**不要**设置这两个属性。

---

## 5. FC-002 换肤验收清单

发布前请逐项确认：

- [ ] 在 app 模块定义了同名 `@color/day_timeline_*`，**未改动组件源码或库资源**
- [ ] `values/` 与 `values-night/` **都**覆盖了，色名集合完全一致
- [ ] 只用了 `format="color"` / `format="dimension"`，**没有无量纲 `dimen`**
- [ ] 浅色与深色下都实际看过：网格线、轴标签、当前时间线、三态块、编辑层、手柄
- [ ] 文字对比度可读（正文建议 ≥ 4.5:1）
- [ ] 单条业务色（`TimelineEvent.color`）的深浅两套由**你自己**准备
- [ ] 深色模式切换后颜色正确（见下方「关于深色模式何时生效」）

### 5.1 关于深色模式何时生效

组件在**视图挂载到窗口时**（`onAttachedToWindow`）检查 `uiMode` 与语言是否变化，
变了就**整份重新解析**资源（E30 / E31）。

- **绝大多数情况你不用管**：切换深色模式时系统通常会重建 Activity，
  视图随之重新挂载，颜色自然就是新的。
- **自己切 `uiMode` 且视图一直挂着**（比如只调 `setDefaultNightMode` 而不重建）：
  组件**不会**在切换的瞬间重新解析。此时调用一次
  `setConfig(TimelineConfig())` 即可触发重解析。
- 组件**没有**「当前是不是夜间」的配色分支 —— 浅深取值全部来自
  `values/` 与 `values-night/` 两份 `colors.xml`（§7.3 强制要求 ②）。

---

## 6. 常见问题

**Q：只覆盖了浅色，深色还是蓝色？**
深色适配完全靠资源。你必须在 `values-night/` 里覆盖同名项。

**Q：覆盖后运行崩溃，说找不到资源？**
多半是深浅两套色名不一致。组件要求两套名称对齐，缺一个就是运行期崩溃。

**Q：业务方改 `TimelineEvent.color` 会覆盖主题色吗？**
不会。业务色只作用于左侧色条（`accentColor`），与 17 项主题色是两条独立通道。

**Q：能不能让某几条日程整块变色？**
`EventBlockPainter` 的 `BlockContext` 里有 `event` 与 `state`，
按 `event.id` 分支即可自行绘制。

**Q：换肤后选中态看不出来？**
`selected` 颜色（`dtColorSelected`）要单独覆盖，且需与常规态明显可辨。
设计侧对此的要求是「与常规态明显可辨」，不是「略深一点」。

---

**相关文档**：[README](../README.md) ·
[完整能力文档](DayTimeline-完整能力文档.md)（55 项配置与全部回调） ·
[交互说明文档](DayTimeline-交互说明文档.md) ·
[产品需求文档](DayTimeline-产品与需求文档.md)（§7.3 语义色项 / §10.2 分层定制） ·
[设计侧定稿交付](DayTimeline-设计侧定稿交付.md)

---

> **本文的换肤能力由测试守护**：`ThemeOverrideTest` 用**资源覆盖**（与业务方接入
> 完全同构的方式）替换三项语义色，断言覆盖值一路贯穿到**真正画出来的像素**，
> 而不是只断言 `getColor()` 能取到值。移除夹具后三条立即转红。