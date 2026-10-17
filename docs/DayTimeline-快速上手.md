# DayTimeline 快速上手

> **目标：5 分钟内完成第一次接入**——从零到界面上出现可点击、可滚动的单日时间轴。
> 做完本文不需要再读别的文档；后续按需跳转到最后一节的链接。
>
> | | |
> |---|---|
> | 对应交付物 | **DL-03 快速上手文档**（PRD §16.3，要求「5 分钟内完成接入」） |
> | 适用版本 | `1.0.0-rc1` |
> | 跑不起来时先看 | [§4 常见问题](#4-常见问题)——八成卡在清单和高度上 |

---

## 0. 五步速览

如果你赶时间，下面五段代码直接抄；抄完再回头读解释。

```kotlin
// 1) 依赖
implementation("com.github.kevinvane:DayTimelineAndroid:1.0.0-rc1")

// 2) 布局
//    <com.github.kevinvane.daytimeline.library.DayTimelineView
//        android:id="@+id/timeline"
//        android:layout_width="match_parent"
//        android:layout_height="match_parent" />

// 3) 数据
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

// 4) 提交
timeline.submitEvents(todayMeetings)

// 5) 监听
timeline.listener = object : TimelineListener {
    override fun onEventClick(event: TimelineEvent) { /* 弹你自己的详情 */ }
}
```

外加一件**不做就会静默失效**的事：清单里声明 `android:supportsRtl="true"`（见 §1.3）。

---

## 1. 接入

### 1.1 引入依赖

`settings.gradle.kts` 里确认有 JitPack：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

模块的 `build.gradle.kts`：

```kotlin
dependencies {
    implementation("com.github.kevinvane:DayTimelineAndroid:1.0.0-rc1")
}
```

> 该坐标由 GitHub 仓库 `kevinvane/DayTimelineAndroid` 经 JitPack 构建。
> 当前发布状态与产物核对记录见[开发指南 · 发布流程与产物核对](DayTimeline-开发指南.md#发布流程与产物核对)。

### 1.2 放置组件

```xml
<com.github.kevinvane.daytimeline.library.DayTimelineView
    android:id="@+id/timeline"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

**高度是新手最容易踩的坑**，组件有两种滚动模式、高度约定不同：

| | 自身滚动（默认） | 外部容器滚动 |
|---|---|---|
| 高度 | 填满父容器（`match_parent`） | `wrap_content` |
| 谁负责滚动 | 组件自身 | 你的外层容器 |
| 组件是否消费垂直手势 | 是 | 否，必须让给外层 |
| 旋转后滚动位置 | 组件自动恢复 | 外层容器负责 |

外部滚动模式多写一个属性：

```xml
<com.github.kevinvane.daytimeline.library.DayTimelineView
    android:id="@+id/timeline"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:dtScrollMode="external" />
```

> ⚠️ 组件**不主动校验**这个约定。高度配错会表现为内容被截断或下方留一大片空白。

### 1.3 清单里声明 `android:supportsRtl`（**必做**）

```xml
<application
    android:supportsRtl="true">
```

**不写这一行不会报错，但 RTL 永远不生效。** Android 框架在应用未声明时会强制 LTR，
`View.setLayoutDirection(LAYOUT_DIRECTION_RTL)` 变成空操作——
组件里那套镜像逻辑（时间轴移到右侧）一次都不会被触发，**没有任何提示**。

组件库无法替你的应用写进清单（清单不随 AAR 合并），所以这行必须由接入方声明。

> 这不是假想：本仓库自己就踩过——镜像实现一直是对的，
> 直到 2026-10-13 补测试时才发现「支持 RTL」长期只由一行代码自证。

---

## 2. 提供数据

业务方**在自己的数据类上实现 `TimelineEvent`**，不需要继承任何基类：

```kotlin
import com.github.kevinvane.daytimeline.library.core.MinuteOfDay
import com.github.kevinvane.daytimeline.library.core.TimelineEvent

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

只有 `start` / `end` 是必须实现的；`content`（块内文字）有默认实现，不写就是空。

### 为什么时间是 `MinuteOfDay` 而不是 `Int`

`MinuteOfDay` 是**私有构造函数的普通类**，只能经 `MinuteOfDay.of(hour, minute)`
或 `MinuteOfDay.ofMinute(当天第几分钟)` 构造。

这样设计的目的是**让「传错时间单位」在编译期就过不去**：
如果 `start` 是裸 `Int`，`start = 90`（想表达 9 点）会被当成 90 分钟 = 1:30，
而编译器完全沉默。换成 `MinuteOfDay` 后这类代码**根本编译不过**。

（Java 调用方同样可用：它是普通类，不是 `value class`。）

---

## 3. 提交与交互

```kotlin
timeline.submitEvents(todayMeetings)

timeline.listener = object : TimelineListener {
    override fun onEventClick(event: TimelineEvent) {
        // 单击：弹你自己的详情
    }

    override fun onEventLongClick(event: TimelineEvent) {
        // 长按：进入拖拽调时间
    }
}
```

提交的数据要满足两条基本约束（不满足时组件**不会崩溃**，而是容错并通过
`onDataIssues` 上抛脏数据）：

- `id` 在当批数据内唯一
- `end` 晚于 `start`，且都落在当天 00:00–23:59 内

### 编辑态：组件不内置任何按钮

组件**不提供**「完成 / 取消 / 删除」按钮——这是 PRD v1.22 的确定行为，不是待补的缺口。
要改时间有两条路：

1. **就地拖拽**：长按进入拖拽态，拖块体改开始时间、拖两端手柄改起止。
   改动通过 `onEventModified` 回调上来，**由你决定是否落库**。
2. **完全接管**：实现 `EditController`，组件把编辑权整个交给你，
   你弹什么表单都行（见 [完整能力文档](DayTimeline-完整能力文档.md)）。

> 想调整吸附粒度、格高等全部 55 项配置，见 [完整能力文档](DayTimeline-完整能力文档.md)。

---

## 4. 常见问题

| 现象 | 多半是 |
|---|---|
| 界面上什么都没有，但没报错 | 忘了 `submitEvents`，或传了空列表 |
| 内容被截断 / 下方一大片空白 | 滚动模式与高度不匹配（见 §1.2 的表） |
| 阿拉伯语、希伯来语下时间轴仍在左侧 | 清单没声明 `android:supportsRtl`（见 §1.3） |
| 改了数据源但界面不变 | 数据类必须是**可变对象或重新构造**；`submitEvents` 接收的是值快照 |
| 块内文字不显示 | 只实现了 `start` / `end`；`content` 需要显式覆写 |
| 看不到「完成 / 取消 / 删除」按钮 | **组件不内置**，见 §3 |

---

## 5. 接下来读什么

按需要挑，不必全读：

| 想知道 | 读 |
|---|---|
| 全部 55 项配置、所有回调、所有定制方式 | [完整能力文档](DayTimeline-完整能力文档.md)（DL-04） |
| 怎么换肤、怎么替换日程块样式 | [主题定制指南](DayTimeline-主题定制指南.md)（DL-05） |
| 点击/长按/拖拽/边界/编辑态的精确规则 | [交互说明文档](DayTimeline-交互说明文档.md)（DL-06） |
| 跑一个真实 demo | 仓库的 `app` 模块；坐标、混淆规则见 [README](../README.md) |
| 从源码构建 / 发版 / 贡献代码 | [开发指南](DayTimeline-开发指南.md) |

> **只想先跑通链路？** 本文五步即可，无需读 README 的其他章节。
> 反过来，已经在用本组件、想查某个回调或配置项的，直接看
> [README](../README.md#回调参考) 与[完整能力文档](DayTimeline-完整能力文档.md)。

---

## 附：本文与 README 的分工

| | 本文（DL-03） | README | 完整能力文档（DL-04） |
|---|---|---|---|
| 定位 | 5 分钟跑通第一条链路 | 接入方速查：坐标、契约、回调、限制 | 组件能力唯一真源 |
| 长度 | 一屏读完 | 约 400 行 | 全量 |
| 配置项 | **不列全表** | **不列全表**（只列常用 7 项） | **全表在此** |
| 读者 | 第一次接入 | 已在用，要查某项 | 需要细节时 |

> **全表只在完整能力文档维护一份。** 副本（`attrs.xml`、`TimelineConfig`、文档）
> 天然会漂移，所以本文与 README **都不许**抄全表——
> `DocsConfigCoverageTest` 会把文档里出现的每一个 `dt` 开头的属性名与 `attrs.xml` 对账，
> 写一个不存在的属性名即刻失败。

> 构建、门禁、发版、贡献代码的内容在[开发指南](DayTimeline-开发指南.md)，
> 不在 README ——README 只面向把库接进自己项目的人。
