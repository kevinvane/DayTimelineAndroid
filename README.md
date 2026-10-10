# DayTimeline

可嵌入任意 Android 应用的**单日 24 小时时间轴视图组件**。以「纵向时间 + 横向重叠分栏」呈现一天日程，并让用户直接在其上完成查看、新建、调整、删除。

- **纵向是时间**：00:00 → 24:00，每小时一格
- **横向是并列**：时间重叠的日程自动并排，不互相遮挡
- **可直接操作**：点=查看 / 长按=编辑 / 拖拽=调整
- **零第三方依赖**：只用 Android 平台 + AndroidX 官方库

---

## 快速上手

### 1. 引入依赖

组件尚未发布到 Maven，构建本仓库后在 app 模块接线：

```kotlin
dependencies {
    implementation(project(":library"))
}
```

### 2. 放置组件

两种滚动模式，**高度约定不同**（见下表）：

```xml
<!-- 自身滚动：填满父容器 -->
<com.github.kevinvane.daytimeline.library.DayTimelineView
    android:id="@+id/timeline"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />

<!-- 外部容器滚动：高度必须等于全天内容高度 -->
<com.github.kevinvane.daytimeline.library.DayTimelineView
    android:id="@+id/timeline"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

| | 自身滚动（默认） | 外部容器滚动 |
|---|---|---|
| 高度 | 填满父容器 | 24 × 格高 + 上下留白 |
| 滚动由谁负责 | 组件自身 | 外层滚动容器 |
| 是否消费垂直手势 | 是 | 否，必须让给外层 |
| 旋转后滚动位置 | 组件自动恢复 | 由外层容器负责 |

> 外部滚动模式下高度配错会截断内容或留白过多。组件不主动校验，请严格按上表设置。

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

> `start` / `end` 的类型是 `MinuteOfDay`（**私有构造函数的普通类**，只能经
> `MinuteOfDay.of(hour, minute)` 等具名工厂构造），业务方拿不到裸 `Int`，
> 因此 `start = 90` 这类把「小时」误当「分钟」的代码**编译不过**。
> **Kotlin 与 Java 业务方均可实现 `TimelineEvent`**：Java 侧需自行声明
> `expiredOverride` / `content` / `color` 三个可选方法的缺省值（与 Kotlin 缺省一致），
> 完整示例见 `:r8test` 模块的 `JavaBusinessEvent.java`。

需要按任务状态而非时间判断是否已过期时，追加 `expiredOverride`（三态推导规则见 PRD §9.4）。

### 4. 提交与监听

```kotlin
timeline.submitEvents(todayMeetings)

timeline.listener = object : TimelineListener {
    // content 是**修改后**的显示内容；null 表示未改标题，"" 表示清空
    override fun onEventCreated(range: IntRange, content: CharSequence?) { /* 自行落库 */ }
    override fun onEventModified(
        event: TimelineEvent,
        range: IntRange,
        content: CharSequence?,
        hasConflict: Boolean,
    ) { }
    override fun onEventDeleted(event: TimelineEvent) { }
    override fun onDataIssues(issues: List<DataIssue>) { /* 数据质量监控 */ }

    // 取消：只用于统计编辑完成率，切勿据此修改数据
    override fun onEditCancelled() { analytics.track("edit_cancel") }
}
```

**组件不修改业务方数据、不做持久化、不发起任何网络请求。** 所有变更只通过事件通知，是否落库由你决定。

---

## 能力清单

| 分类 | 能力 |
|---|---|
| 数据 | 提交列表 / 单条更新 / 单条删除 / 切换日期 / 指定当前时间 |
| 视图 | 跳转指定时刻 / 刷新当前时间线 / 读取与恢复滚动位置 |
| 交互 | 点击 / 长按 / 空白新建 / 拖拽移动 / 手柄改时长 / 完成 / 取消 / 删除（二次确认）/ 边缘自动滚动 |
| 配置 | 17 项语义色项 + 全部尺寸 + 时间格式 + 滚动模式 + 编辑层行为，均支持代码与 XML 两种方式 |
| 定制 | 四层：参数配置 / 网格绘制 / 块内容 / 交互接管，**业务方无需修改组件源码** |

### 定制示例

```kotlin
// 第三层：完全替换日程块内容
timeline.eventBlockPainter = object : EventBlockPainter {
    override fun paint(canvas: Canvas, ctx: BlockContext, colors: TimelineColors, paints: Paints) {
        // 坐标系与 RTL 镜像已由组件处理好，只管画
    }
}
```

### XML 配置

每个配置项都可写在布局里，**与代码配置一一对应、效果完全一致**：

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

`setConfig()` 采用**合并**语义：只会覆盖你显式写出的字段，XML 里其余配置保持不变。
因此可以放心「XML 打底 + 代码里再微调几项」。

---

## 工程结构

```
library/src/main/java/.../library/
├── DayTimelineView.kt    # 对外唯一入口
├── api/                  # 对外契约：配置、事件、定制扩展点
├── core/                 # 纯 Kotlin，零 android.* 依赖 —— 覆盖率 ≥ 90% 的主体
├── paint/                # 绘制实现、17 项语义色项、无障碍虚拟视图
└── internal/             # 尺寸解析、状态保存
app/                      # demo 与验收载体，不含可复用逻辑
```

`core/` **禁止 import 任何 `android.*`**。这条纪律是覆盖率能达标的前提——纯 Kotlin 类可用
JVM 单测直接跑，不需要设备也不需要 Robolectric。

---

## 构建与验证

```powershell
.\gradlew.bat :library:assembleDebug        # 编译组件库
.\gradlew.bat :app:assembleDebug            # 编译 demo
.\gradlew.bat :app:installDebug             # 装到设备/模拟器
.\gradlew.bat :library:test                 # JVM 单元测试（不需要设备）
.\gradlew.bat :library:lintDebug             # 平台规范检查
.\gradlew.bat :library:verifyCoreCoverage   # 核心逻辑覆盖率门禁 ≥ 90%
.\gradlew.bat :library:verifyAllCoverage     # 全库覆盖率门禁 ≥ 75%（需设备）
.\gradlew.bat :library:connectedAndroidTest # 仪器测试（需设备/模拟器）
```

### 当前门禁状态

| 门禁 | 状态 | 说明 |
|---|:--:|---|
| 核心逻辑覆盖率 ≥ 90% | **达标** | 实际 91.08% |
| 平台规范检查 0 错误 0 警告 | **达标** | `lintDebug` 通过 |
| 单元测试 | **达标** | 100 个用例全绿，含 U1–U16 逐条验收 |
| 资源契约（真机） | 待验证 | 仪器测试已就位，需设备执行 |
| 全库覆盖率 ≥ 75% | **未达标** | View 层只能由仪器测试覆盖，见下 |
| 死代码 0 处 | 部分 | 已手工清理，未接入自动检查工具 |
| R8 混淆验证 | 待验证 | 规则已就位，尚无 minify 消费端验证 |

> **关于全库覆盖率**：`api` / `paint` / `internal` 三个包（含 View 绘制与无障碍）无法用 JVM
> 单测覆盖，而零依赖约束排除了 Robolectric，因此该口径必须靠仪器测试。相关门禁在无设备
> 环境下必然不达标——这是约束冲突，不是实现缺陷。

> **编译通过不等于能运行。** 曾出现过 lint 干净、107 个单测全绿、覆盖率达标，
> 但真机启动即崩的情况：`dimens.xml` 里用 `<item format="float" type="dimen">` 声明
> 无量纲比值，AAPT2 按 `format` 编译成 `TYPE_FLOAT`，而 `Resources.getDimension()`
> 只接受 `TYPE_DIMENSION`。此类问题对 JVM 单测与 lint 完全不可见，
> 因此仓库现已有真机仪器测试专门守住资源契约。

---

## 已知限制

只支持单日，不支持跨天；最小粒度为分钟；不处理重复日程规则；不做数据持久化；不提供图形化配置界面；同一时刻的日程等宽并排。跨天与持久化由业务方在更高层实现。

---

## 文档

| 文档 | 内容 |
|---|---|
| [产品需求文档](docs/DayTimeline-产品与需求文档.md) | 需求、界面与交互规格、数据契约、质量要求、验收标准。**唯一需求来源** |
| [技术方案与实施计划](docs/DayTimeline-技术方案与实施计划.md) | 架构决策 AD-01~AD-23、任务拆解、门禁落地方案、开放问题 |
| [日程块表单输入方案](docs/DayTimeline-日程块表单输入方案.md) | PRD v1.3 新增的表单输入能力设计（AD-22） |
| [AGENTS.md](Agents.md) | 面向 AI 协作者的仓库指引与硬性约束 |

三份文档均为 UTF-8 中文，用支持 UTF-8 的工具阅读（PowerShell 控制台需先设 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`）。

---

## 环境

- JDK 17，Gradle 8.13，AGP 8.13.2，Kotlin 2.0.21，`minSdk 23`，`targetSdk 36`
- View 体系（AppCompat + Material 3 + ConstraintLayout），**不是 Compose**
- `local.properties` 需包含 `sdk.dir`，该文件被 gitignore，新克隆的仓库需自行创建

## 许可

Apache-2.0
