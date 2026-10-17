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

**Apache License 2.0** · `minSdk 23` · View 体系（非 Compose） · Kotlin 与 Java 业务方均可接入

---

## 目录

- [安装](#安装)
- [5 分钟接入](#5-分钟接入)
- [兼容性](#兼容性)
- [数据契约](#数据契约)
- [配置](#配置)
- [四层定制](#四层定制)
- [回调参考](#回调参考)
- [混淆](#混淆)
- [无障碍](#无障碍)
- [已知限制](#已知限制)
- [示例应用](#示例应用)
- [文档](#文档)
- [许可](#许可)

---

## 安装

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// 模块 build.gradle.kts
dependencies {
    implementation("com.github.kevinvane:DayTimelineAndroid:1.0.0")
}
```

| 版本 | 状态 |
|---|---|
| **`1.0.0`** | 当前版本，首个正式版 |
| `1.0.0-rc1` | 上一版，产物已逐项核对 |

> 「1.0.0 的产物核对结果」见[质量报告](docs/DayTimeline-质量报告.md) §7。
> 发布链路上的绿灯**不证明产物正确** —— 上一版 `1.0.0-beat` 就因 tag 打在陈旧提交上，
> JitPack 返回 `ok` 而产物缺 3 个类、5 个方法。

### 接入前置条件：清单里要声明 `android:supportsRtl="true"`

```xml
<application
    android:supportsRtl="true">
```

**不写这一行，Android 框架会强制 LTR**——`View.setLayoutDirection(LAYOUT_DIRECTION_RTL)`
变成空操作，组件的 RTL 镜像（时间轴移到右侧）**永远不会被触发，且没有任何报错**。

组件库无法替你的应用写进清单（清单是接入方的，不随 AAR 合并），因此这一行必须由你声明。
本仓库正是踩中了这个坑：镜像实现一直是对的，但组件库自己的测试 APK 也没声明这一行，
于是「支持 RTL」长期只由一行代码和几段 KDoc 自证 —— 2026-10-13 补测试时才暴露出来。

---

## 5 分钟接入

以下是一个**可直接编译**的最小示例。完整步骤、两种滚动模式的写法与六个常见坑见
[快速上手](docs/DayTimeline-快速上手.md)。

```kotlin
// 1. 在业务数据类上实现 TimelineEvent，不需继承任何基类
data class Meeting(
    val id: String,
    val from: LocalTime,
    val to: LocalTime,
    val title: String,
) : TimelineEvent {
    override val start get() = MinuteOfDay.of(from.hour, from.minute)
    override val end get() = MinuteOfDay.of(to.hour, to.minute)
    override val content get() = title
}
```

```xml
<!-- 2. 放置组件 -->
<com.github.kevinvane.daytimeline.library.DayTimelineView
    android:id="@+id/timeline"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:dtHourHeight="56dp"
    app:dtSnapMinutes="15" />
```

```kotlin
// 3. 提交数据与监听事件
timeline.submitEvents(todayMeetings)

timeline.listener = object : TimelineListener {
    override fun onEventClick(event: TimelineEvent) {
        showMyDetail(event)          // 点 = 查看，你自己弹详情
    }
    override fun onEventLongClick(event: TimelineEvent) {
        // 长按 = 编辑
    }
    override fun onEventCreated(range: IntRange, content: CharSequence?) {
        myRepository.insert(range, content)   // 自行落库
    }
}

// 4. 编辑态接管：组件不内置「完成/取消/删除」按钮
timeline.editController = object : DayTimelineView.EditController {
    override fun onEnterEditing(draft: EditDraft): Boolean {
        showMyForm(prefill = draft)
        return true                   // 返回 true = 接管，退出路径交给我
    }
}

// 表单「确定」：唯一发出数据变更的出口
timeline.applyEdit(EditResult(range = 540..600, content = "Review"))
timeline.confirmEdit()

// 表单「取消」：结构上不可能带出数据变更
timeline.cancelEdit()
```

> **组件不修改你的数据、不做持久化、不发起任何网络请求。** 所有变更只通过事件通知，
> 是否落库由你决定。
>
> 特别地：**取消编辑绝不产生任何数据变更**。`onEditCancelled` 只用于统计编辑完成率，
> 切勿据此修改数据。详见[数据契约](#数据契约)中的 D3 条款。

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
| 混淆 | 随 AAR 打包 `consumer-rules.pro`，**无需任何配置** |

已实测的形态：手机（Pixel / API 28、API 34 AVD）、平板（API 23 真机，mdpi）、
折叠屏展开与折叠（两种几何各 115 条仪器测试全绿）。

### ⚠️ 屏幕缺口：接入方必须自行避让

组件**不消费 `WindowInsets`**——它不知道宿主窗口形态，也不该替宿主决定边距。
只取 `systemBars()` 是**不够**的（按契约它不含缺口）：

```kotlin
ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
    v.setPadding(
        maxOf(bars.left, cutout.left), maxOf(bars.top, cutout.top),
        maxOf(bars.right, cutout.right), maxOf(bars.bottom, cutout.bottom),
    )
    insets
}
```

> **竖屏看不出问题，横屏才会翻车**：竖屏缺口在顶部、状态栏 inset 恰好盖住它；
> 横屏缺口移到侧边时状态栏盖不住，时间轴左侧的**小时刻度标签会整列画进缺口区**。

### ⚠️ 多窗口（分屏）：请在你的目标设备上自行验证

`targetSdk = 36` 时 `resizeableActivity` 默认 `true`，组件在窗口尺寸变化时走
`onSizeChanged → relayout()` 重排，滚动位置与选中态由 `onSaveInstanceState` /
`onRestoreInstanceState` 保留（`scrollOffset` / `viewDate` / `selectedId`），
**进程不会因 resize 而重建**——这一段已实测。

但**真分屏的多任务会话本项目未能验证**：任务栈分槽、焦点切换、跨应用并存三段
没有实测覆盖。逐项自查建议见[完整能力文档 §8.3](docs/DayTimeline-完整能力文档.md#83-多窗口分屏请在你的目标设备上自行验证)。

---

## 数据契约

业务方数据与组件之间**唯一**的契约。接口而非基类，不侵入你已有的数据模型。

| 成员 | 类型 | 说明 |
|---|---|---|
| `id` | `String` | 唯一标识。重复时组件照常渲染并上抛数据异常，**不崩溃** |
| `start` / `end` | `MinuteOfDay` | 起止时刻。`end` 必须晚于 `start`，否则组件兜底修正 |
| `expiredOverride` | `Boolean?`（默认 `null`） | `true` 强制按「已过」配色；`false` 跳过「已过」判定按时间定 ongoing/upcoming；`null` 纯按时间推导 |
| `content` | `CharSequence?`（默认 `null`） | 显示内容，格式不限。`null` 时读屏用占位文案 |
| `color` | `Int?`（默认 `null`） | 业务色（ARGB）。`null` 用组件默认配色 |

**Kotlin 与 Java 业务方均可实现。** Java 侧需自行声明 `expiredOverride` / `content` /
`color` 三个可选方法的缺省值（返回 `null`）。

`MinuteOfDay` 是「一天中的第几分钟」，**私有构造函数的普通类**，只能经具名工厂构造：

```kotlin
MinuteOfDay.of(9, 30)          // 09:30
MinuteOfDay.parse("09:30")     // 非法格式返回 null，不抛异常
t.plusMinutes(15)
t.isStrictlyAfter(other)       // 相接（e1 == s2）不算重叠
```

**刻意不用 `@JvmInline value class`** —— 它会让 Kotlin 把方法名改写成
`getStart-ZruiD9E()` 这类**非法 Java 标识符**，导致 Java 业务方根本无法实现 `TimelineEvent`。

> `of()` **刻意不做钳制**：脏数据必须能原样进来，组件才能侦测到并上抛。
> 在构造时就钳制会让那些检查变成永久死代码。

> `color` 刻意**不并入** 17 项语义色项：那 17 项靠 `values/` + `values-night/` 适配深浅色，
> 覆盖会破坏这套机制。深浅色适配由你自行按 `Configuration.uiMode` 处理。

### D3：取消绝不产生数据变更

> 这是本组件最硬的一条约定，已由 `:library` 与 `:r8test` 的仪器测试分别守住两侧。

- `cancelEdit()` 走的是 `EditSession.cancel()`，返回一个**无字段**的结果对象——
  在类型层面就**无法表达一次数据变更**。
- 取消路径唯一允许的回调是 `TimelineListener.onEditCancelled`，它不携带任何数据。
- 确认编辑只有 `confirmEdit()` 一个出口，不存在「先确认再回滚」的写法。

### 脏数据：容错 + 事件上抛

组件对不合规数据**一律容错修正，不崩溃，且修正对用户不可见**，同时通过 `onDataIssues` 上抛
（5 类：`NON_POSITIVE_DURATION` / `END_AFTER_MIDNIGHT` / `START_BEFORE_MIDNIGHT` /
`DUPLICATE_ID` / `BLANK_ID`）。`detailOf()` 返回的 `range` 已是修正后的值，**可直接显示**。

逐项说明见[完整能力文档](docs/DayTimeline-完整能力文档.md)。

---

## 配置

每个配置项都支持 **XML** 与 **代码**两种方式，二者**一一对应、效果完全一致**：

```xml
<com.github.kevinvane.daytimeline.library.DayTimelineView
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:dtHourHeight="64dp"
    app:dtShowTimeSubtitle="true"
    app:dtSnapMinutes="5"
    app:dtScrollMode="self" />
```

```kotlin
// 代码侧的尺寸字段单位是 **px**，需要自己从 dp 换算
timeline.setConfig(TimelineConfig(
    hourHeight = (64 * resources.displayMetrics.density).toInt(),
    showTimeSubtitle = true,
))
```

`setConfig()` 采用**合并**语义：只覆盖你显式写出的字段，其余保持不变。
`TimelineConfig` 的所有字段都是可空类型，`null` 表示「用资源默认值」。

本 README 只列最常改的几项：

| XML 属性 | 代码字段 | 默认值 | 什么时候需要改 |
|---|---|---|---|
| `dtHourHeight` | `hourHeight` | `56dp` | 想让一格更高、时间轴更疏 |
| `dtScrollMode` | `scrollMode` | `self` | 页面外层已有滚动容器时改 `external` |
| `dtShowTimeSubtitle` | `showTimeSubtitle` | `false` | 想在块内显示起止时间 |
| `dtSnapMinutes` | `snapMinutes` | `15` | 想让吸附更细或更粗（`0` 关闭吸附） |
| `dtAutoLocateOnFirstShow` | `autoLocateOnFirstShow` | `true` | 不希望打开即定位到当前时间 |
| `dtBlockTextSize` | `blockTextSize` | `12sp` | 块内文字太小 |
| `dtMinTouchTarget` | `minTouchTarget` | `48dp` | **只应调大，不应调小**（无障碍硬要求） |

**全部 55 项配置见[完整能力文档](docs/DayTimeline-完整能力文档.md)** —— 那里是唯一真源，
并且有一条测试断言「文档里的属性集合 ≡ `attrs.xml` ≡ `TimelineConfig` 字段集合」。

**颜色**走另一条通道：17 项语义色项（`dtColorBackground` … `dtColorEditHandle`）
默认取自 `values/colors.xml` 与 `values-night/colors.xml`，深浅色各自一套。
**换肤只需在自己的资源里覆盖同名项，勿改组件源码。**

### 两种滚动模式

| | 自身滚动（默认） | 外部容器滚动 |
|---|---|---|
| 高度 | 填满父容器（`match_parent`） | `wrap_content`，等于 `24 × 格高 + 上下留白` |
| 滚动由谁负责 | 组件自身 | 外层滚动容器 |
| 是否消费垂直手势 | 是 | 否，必须让给外层 |
| 旋转后滚动位置 | 组件自动恢复（`onSaveInstanceState`） | 由外层容器负责 |

> ⚠️ 外部滚动模式下高度配错会截断内容或留白过多。**组件不主动校验，请严格按上表设置。**

---

## 四层定制

**你永远不需要修改或复制组件源码。**

| 层 | 入口 | 能做什么 |
|---|---|---|
| 1. 参数配置 | `TimelineConfig` / XML 属性 | 55 项尺寸、颜色、行为 |
| 2. 网格绘制 | `timeline.gridPainter` | 完全替换刻度线 / 轴标签 / 当前时间线的绘制 |
| 3. 块内容 | `timeline.eventBlockPainter` | 完全替换日程块内部的画什么、怎么画 |
| 4. 交互接管 | `timeline.editController` | 接管创建/编辑流程与删除二次确认 |

```kotlin
// 第三层：完全替换日程块内容
timeline.eventBlockPainter = object : EventBlockPainter {
    override fun paint(canvas: Canvas, ctx: BlockContext, colors: TimelineColors, paints: Paints) {
        // 坐标系与 RTL 镜像已由组件处理好，只管画
    }
}
```

两个 Painter Context 都是**复用对象**：组件只持有一个实例，每帧改写其字段。
**不要把它们存进字段长期持有**，只在 `paint()` 调用期间使用。
字段清单与坐标系约定见[完整能力文档](docs/DayTimeline-完整能力文档.md)。

---

## 回调参考

`TimelineListener`（全部可选覆写）：

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

`DayTimelineView.EditController`（全部可选覆写）：

| 回调 | 返回 `true` 的含义 |
|---|---|
| `onEnterEditing(draft): Boolean` | 接管。组件不再响应「点外部 = 取消」，退出路径交给你 |
| `onDone(draft, result): Boolean` | 已由你处理，组件不再自行发事件 |
| `onDelete(): Boolean` | 已由你处理删除 |
| `onCancel(draft)` | 无返回值，仅供你关闭自己的界面 |
| `confirmDelete(context, eventTitle, onConfirmed)` | 提供删除二次确认。有默认实现（`AlertDialog`），可覆写 |

三条容易踩的细节：

- `content` 的三态语义必须区分：`null` = **未改标题**、`""` = **清空标题**、其他 = 设置为该值。
- `hasConflict == true` 表示业务方在编辑期间改了同一条日程。组件**保留了用户的编辑状态**，
  是否覆盖由你决定。
- `onEnterEditing` 与 `enterEditModeAndNotify()` 要分清：业务方详情弹窗点「删除」只需要编辑态、
  **不该**弹表单；用 `enterEditMode()` 与 `enterEditModeAndNotify()` 区分，
  否则会出现「点编辑弹出两个表单」。

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
| 不内置编辑按钮 | 「完成 / 取消 / 删除」需业务方通过 `EditController` 接管实现。**PRD v1.22 已取消「可配置是否内置」这项需求**，不是待补的缺口 |
| 不提供图形化配置界面 | 配置只经 XML / 代码 |
| 同刻日程等宽并排 | 总列数取当天最大重叠数，不按内容长度做流式宽度 |
| 窗口避让由接入方负责 | 组件不消费 `WindowInsets`。缺口避让必须并入 `displayCutout()`，否则横屏下小时刻度标签会落进缺口区。见[兼容性](#兼容性) |
| 多窗口会话未实测 | resize 路径已验证；分屏的任务栈分槽 / 焦点切换 / 跨应用并存三段**没有实测覆盖**，请自行验证 |
| **文案暂仅有中文** | 组件内文案全部走 `strings.xml`（可被业务方覆盖），但库自身**未提供 `values-xx` 翻译**。非中文环境会显示中文，请在自己的资源里覆盖同名项 |

### 以下三项至今没有任何实测支撑

它们**不阻塞发版**，但你若依赖这些能力，请自行验证：

| 项 | 状态 |
|---|---|
| 依赖漏洞扫描 | 门禁已建，**从未跑通**（须在 CI 跑；本机出口被限流） |
| 1 小时耗电 | **从未实测**。耗电暴露面经代码走查确认极小，但**「暴露面小」不等于「实测达标」** |
| 真多窗口（分屏） | resize 路径已验证；**多任务会话三段未覆盖** |

**「发布阻断项 0 项」不等于「全部验证通过」。** 依据见[质量报告](docs/DayTimeline-质量报告.md) §1.2。

色值方面：§7.3 的 17 项语义色项已换成[设计侧定稿](docs/DayTimeline-设计侧定稿交付.md)取值，
「低对比度」下限定为 **≥ 3.0:1**，色值门禁为 **0 项登记偏差**。
若你此前按 Material 3 baseline 占位色做过视觉验收，需重走一遍。

---

## 示例应用

`:app` 模块是一个只做一件事的 demo：把组件接起来，跑通完整闭环。

- XML 配置尺寸与行为 + 代码补充一项，验证两条配置路径可叠加
- 接管 `EditController`，弹出**自己实现的**表单（`BottomSheetDialog`）
- 单击弹出**自己实现的**只读详情弹窗（`PopupWindow`）
- 删除二次确认、表单取消、详情弹窗取消的全部收尾路径

---

## 文档

**接入方看这三份就够：**

| 文档 | 内容 |
|---|---|
| [快速上手](docs/DayTimeline-快速上手.md) | **5 分钟完成第一次接入**。五步速览 + 六个常见坑 |
| [完整能力文档](docs/DayTimeline-完整能力文档.md) | **全部 55 项配置、全部回调、四层定制、数据契约**。组件能力的唯一真源，有测试守护其与源码一致 |
| [交互说明文档](docs/DayTimeline-交互说明文档.md) | 点击/长按/拖拽/边界/编辑态的精确规则与边界 |
| [主题定制指南](docs/DayTimeline-主题定制指南.md) | **如何换肤、如何替换日程块样式**。四条换肤通道 + FC-002 验收清单 |

**维护者与贡献者：**

| 文档 | 内容 |
|---|---|
| [开发指南](docs/DayTimeline-开发指南.md) | 环境与构建、质量门禁现状、工程结构、**发布流程与产物核对清单** |
| [AGENTS.md](AGENTS.md) | 面向 AI 协作者的仓库指引、硬性约束与已踩过的坑 |
| [产品需求文档](docs/DayTimeline-产品与需求文档.md) | 需求与验收标准的唯一来源 |
| [技术方案与实施计划](docs/DayTimeline-技术方案与实施计划.md) | 架构决策、任务拆解、门禁落地方案 |

其他：[`CHANGELOG.md`](CHANGELOG.md)（变更记录）、
[日程块表单输入方案](docs/DayTimeline-日程块表单输入方案.md)、
[对比度偏差复核清单](docs/DayTimeline-对比度偏差复核清单.md)。

所有文档均为 UTF-8 中文。PowerShell 控制台需先设
`[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` 才能正常显示。

---

## 许可

本项目采用 [Apache License 2.0](LICENSE)，许可证全文见仓库根目录的 [`LICENSE`](LICENSE) 文件。

> 重新分发 AAR 时请自行附带许可证 —— **AAR 内不含 `LICENSE`**（AGP 与 JitPack 都不附带）。