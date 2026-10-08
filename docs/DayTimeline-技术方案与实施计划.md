# DayTimeline 技术方案与实施计划

| 项 | 内容 |
|---|---|
| 文档版本 | v0.5（草案） |
| 状态 | 待评审 |
| 编写日期 | 2026-09-30 |
| 对应 PRD | `docs/DayTimeline-产品与需求文档.md` v1.1 |
| 文档读者 | 研发、测试、设计 |

---

## 1. 文档定位与边界

PRD §1.2 明确把「技术架构设计」「代码实现方案」列为**不属于 PRD**，并声明技术实现细节由研发另行输出。本文档就是那份缺失的产出物。

| 本文档负责 | 本文档不负责 |
|---|---|
| 架构与技术选型（含被排除方案及理由） | 重新定义产品需求（需求有歧义时提回 PRD 修订） |
| 关键技术决策及其依据 | 具体排期与人力安排（沿用 PRD §16.2） |
| 任务拆解到可认领粒度，标注覆盖的需求编号 | 设计稿与色值（属 M0 设计交付） |
| 验证方式与质量门禁的落地方式 | 商业推广计划 |
| 需要产品/研发拍板的开放问题 | — |

**术语沿用 PRD**：需求编号 FR/FI/FD/FC/UF/E/U/Q/T/D/K/N/R/L 含义不变，本文档新增 `AD-xx` 表示技术决策。

**排期口径**：里程碑、出口标准、人天总量以 PRD §16.2 为准，本文只在其内部拆任务。若本文的拆解与 §16.2 冲突，以 §16.2 为准并回改本文。

---

## 2. 架构总览

### 2.1 渲染方案选型（AD-01）

**决策：整个时间轴由单个 `DayTimelineView : View` 在 `onDraw` 中用 Canvas 绘制，不创建任何子 View。** 日程块、编辑层、编辑手柄、当前时间线全部是绘制指令，不是 View。

**依据**（这一条是被硬约束锁死的，没有选择空间）：

| 约束 | 出处 | 含义 |
|---|---|---|
| 网格区常驻界面元素数 = 0 | §12.1 / D16 | 网格不能由 View 组成 |
| 严禁持续不收敛的重绘循环 | D17 | 不得依赖常驻 View 树的重新布局来驱动刷新 |
| 核心逻辑独立于界面、可自动化覆盖 | T2 | 逻辑不能寄生在 View 生命周期里 |
| 零第三方依赖 | K10 / T1 | 排除第三方图表与调度控件 |

**已排除方案**：

| 方案 | 排除理由 |
|---|---|
| `RecyclerView` + 每小时/每日程一个 item View | 每屏常驻 15+ 个 View，直接违反「常驻元素数 = 0」；且 View 复用会带来 D4 首帧抖动风险 |
| `ViewGroup` + 子 View 复用 | 同上；额外承担 measure/layout 开销，与 §12.1「全量布局 200 条 ≤ 4ms」冲突 |
| 第三方时间轴/图表控件 | K10 零依赖；且无法满足 §7.9 W1–W6 的精确宽度语义 |

**代价（必须正视）**：绘制、命中测试（hit testing）、滚动、无障碍全部需要手写。这是本方案的真实成本，不能假装没有。§6 的任务拆解中 M2 会因此比普通 View 组件更重。

### 2.2 模块与包结构

```
library/src/main/java/com/github/kevinvane/daytimeline/library/
├── DayTimelineView.kt          # 对外唯一入口 View，公开配置/事件 API
├── api/                        # 对外契约（稳定边界，@JvmOverloads 等 Java 友好门面）
│   ├── TimelineEvent.kt        # 业务方数据契约（interface，FD-001）
│   ├── TimelineListener.kt     # 事件回调（§11.3 / §15）
│   ├── TimelineConfig.kt       # 参数配置对象（第一层定制）
│   └── DataIssue.kt            # 数据异常（§9.3）
├── core/                       # 纯 Kotlin，无任何 android.* 导入 → 可 JVM 单测（T2）
│   ├── Time.kt                 # MinuteOfDay 值类型、吸附、时长兜底
│   ├── EventSanitizer.kt       # 脏数据修正 + 异常收集（§9.3 / E3–E7）
│   ├── TimeStateResolver.kt    # 已过/进行中/未到推导 + 覆盖规则（§9.4）
│   ├── OverlapLayoutEngine.kt  # 重叠分栏与宽度分配（§7.9 W1–W6 / D1）
│   └── Geometry.kt             # 分钟 ↔ 像素换算
├── paint/                      # 绘制实现
│   ├── GridPainter.kt          # 第二层定制扩展点（刻度线/标签/当前时间线）
│   ├── EventBlockPainter.kt    # 第三层定制扩展点（日程块内容）
│   └── Theme.kt                # 17 项语义色项解析（§7.3）
├── scroll/                     # 两种滚动模式（§8.6 / AD-04）
└── internal/                   # 状态机、刷新收敛、增量 diff、状态保存
```

`core/` **禁止 import 任何 `android.*`**。这条纪律是覆盖率指标（核心逻辑 ≥ 90%）能达标的前提——纯 Kotlin 类可用 JUnit 直接跑，不需要 Robolectric 或设备。构建时应加检查手段（见 §5.2）。

`app/` 是 demo 与验收载体，不含任何可复用逻辑。

### 2.3 依赖现状（必读）

- **`:app` 当前并不依赖 `:library`**。demo、冒烟、性能测试都做不了，M1 第一件事就是补上。
- `library` 当前**没有 `src/main/res`**。需要新建 `values/`（dimens/colors/attrs/strings）、`values-night/`、`xml/`。
- `library/consumer-rules.pro` **是空文件**。R8 保留规则还没写（§12.5 / T7 要求验证）。
- 无 CI、无 lint baseline、无覆盖率工具、无死代码检查、无 ktlint/detekt。§12.4 的门禁目前**一条都跑不起来**，M1 需要一次性补齐。

---

## 3. 关键技术决策

每条决策格式：**决策 → 依据 → 影响/代价 → 待验证**。

### AD-01　单 View + Canvas 全量自绘

见 §2.1。补充要点：

- 坐标系：以「00:00 顶部 + 分钟」为逻辑坐标，绘制时统一减去 `scrollOffset` 并加时间轴区域偏移。
- 内容总高度 = `24 × 格高 + 顶部留白 + 底部留白`（§19.2 高度约定，外部滚动模式直接作为 View 高度）。
- 视口裁剪由 Canvas 天然完成，不需手动裁剪逻辑；只绘制与视口相交且非零高度的块（D4 尺寸不为负的守卫点之一）。

### AD-02　时间用编译期安全的值类型（同时关闭 R9）

**决策**：

```kotlin
@JvmInline
value class MinuteOfDay private constructor(val minuteOfDay: Int) {
    companion object {
        fun of(hour: Int, minute: Int): MinuteOfDay   // 唯一正常构造入口
        fun parse(text: String): MinuteOfDay          // "09:30"
        val START_OF_DAY: MinuteOfDay
        val END_OF_DAY: MinuteOfDay                   // 24:00
    }
}
```

业务方契约用 `interface`，不要求继承（FD-001）：

```kotlin
interface TimelineEvent {
    val id: String
    val start: MinuteOfDay
    val end: MinuteOfDay
    val expiredOverride: Boolean? get() = null   // §9.4 状态覆盖
}
```

**依据**：D18（传错单位在编译期不可能）、D19（编译期类型保障）、§9.1「时间用几点几分表达」。

**关键结论 —— 风险 R9 可以关闭**：`value class` 是**编译期**特性，编译后展开为 `int`，**不依赖任何运行时 API 或 desugaring**。因此「最低支持版本 23」与「D18 编译期保障」**不构成冲突**，R9 提出的二选一（① 抬高 minSdk / ② 降级为运行期校验）**都不必选**。R9 应在 M1 用最小样例验证后标记为已关闭。

**代价**：
- `value class` 会被擦除为 `int`，Java 调用方看不到这个类型。对外 API 需提供 Java 可见的入口（`setEvents(List<TimelineEvent>)` 中的对象类型是 interface，问题不大；`MinuteOfDay` 的工厂需在 `api/` 层加 `@JvmStatic` 门面）。
- 反射/序列化场景下类型信息丢失，需在 `api/` 显式提供转换方法。
- 私有构造函数是编译期保证的关键，**不可为了方便改成 public 或加 `operator invoke`**。

**待验证（M1）**：minSdk 23 + Kotlin 2.0.21 下产物无额外依赖；Java 调用方可用性；R9 正式关闭并同步更新 PRD §18。

### AD-03　重叠分栏与宽度分配（核心算法）

**决策**：`OverlapLayoutEngine.layout(events, availableWidth): List<PlacedBlock>`，**纯函数、无状态、可单测**。它是 D1 与 §14.2 U1–U16 的唯一判定实现。

算法（严格按 §7.9 W1–W6）：

| 步 | 动作 | 对应规则 |
|---|---|---|
| 0 | 先经 `EventSanitizer` 修正 | §9.3 |
| 1 | **内部排序**：按 `(start, id)` 升序 | D1④ / U10：结果与传入顺序无关 |
| 2 | 扫描线 + 最小堆求最大同时重叠数 `C` | W1 |
| 3 | 列分配：按序贪心首次适配；扫过某条目的开始时间前，释放所有已结束条目占用的列 | U5 空列复用；W1 |
| 4 | 基准宽度 `base = 可用宽 / C`（列间扣 `日程块间距`） | W2 |
| 5 | 逐条向右探测：相邻列在其纵向区间内**完全空闲**则并入，遇占用列立即停止 | W3 / W5 / U15 |
| 6 | 左边界固定不动，仅向右变宽；单条最多占满整行 | W6 / W4 / U16 |

**顺序无关性的实现要点**：第 1 步排序是 U10 成立的前提。排序键必须包含 `id`，否则同一开始时间的多条日程（D25/E25 全同时）排序不稳定，U10 会失败。`id` 完全重复时（E7）结果仍需稳定且不崩溃。

**复杂度与风险**：
- 常见场景 O(n log n + n·C)，200 条 C 通常 < 10，充裕。
- **最坏情况 U12**（200 条完全同时，C = 200）：第 5 步的逐条探测退化为 O(n·C) = 40000 次区间比较。仍在毫秒级，但**必须在 M2 实测**（R1 应对措施）。
- 若实测不达标，预案：第 5 步改为「先求出每列的占用区间表，再对每条日程一次扫出连续空闲列数」，可降到 O(n log C)。**先实现简单版，测完再优化**（YAGNI）。

**单元测试要求**：U1–U16 必须**逐条**有对应测试用例，其中 U5（空列复用）、U10（顺序无关）、U15（遇占用列停止扩展）是回归价值最高的三条。

### AD-04　两种滚动模式的手势归属（最高风险交互点）

**决策**：滚动模式由 `TimelineConfig.scrollMode` 决定，两种模式的高度约定严格按 §8.6。

| | 自身滚动（默认） | 外部容器滚动 |
|---|---|---|
| 高度 | 填满父容器 | `24 × 格高 + 上下留白` |
| 滚动实现 | 自持 `OverScroller` + `GestureDetector`（均为平台 API，零依赖） | 不处理滚动 |
| 垂直手势 | 消费 | **不消费**，让给外层 |
| 旋转恢复 | 自恢复 scrollOffset | 只恢复查看日期，滚动位置交外层 |

**必须澄清的冲突点（§8.6 未明说，R2 的核心）**：外部滚动模式下「不消费垂直手势」与 FI-003/FI-004/FI-005（点击、长按、空白新建）以及 FI-006（编辑态拖拽，本质是垂直移动）**必须同时可用**。拟定规则：

- **空白处垂直拖动** → 不消费，交给外层（这是「不抢手势」E29 的实际含义：不能妨碍下拉刷新与侧滑返回）。
- **点击 / 长按** → 正常处理（`GestureDetector` 的单击/长按不依赖移动）。
- **长按日程块后进入编辑态，再垂直拖拽** → 消费（此时已进入编辑态，不再是「滚动意图」）。
- 进入编辑态前若位移超过阈值 → 放弃编辑意图，判为滚动，交给外层。

这套规则必须在 M3 与 M4 联调验证，E29 是它的验收项。

**其它交互规格**：拖拽触发阈值 = 系统标准阈值 × 0.3（§7.2）；边界回弹关闭（§8.5，避免与拖拽冲突）；首次定位 = 当前时间向上偏移约 1/3 屏。

### AD-05　无障碍需要自建虚拟视图

**决策**：使用 `androidx.customview.widget.ExploreByTouchHelper`（**AndroidX 官方库，非第三方**）为每个**视口内可见**的日程块提供虚拟无障碍节点。

**依据**：Q8 / UF-002 / UF-003 / FI-016（外部键盘方向键遍历）。

**影响与风险**：
- 这是 AD-01 全量自绘的直接成本，**不能推迟到最后**。R7 已登记「无障碍被压缩排期」，需在 M5 出口标准中锁死。
- 需实现 `getVisibleVirtualViews` / `onPopulateNodeForVirtualView` / `onPerformActionForVirtualView` 三组回调。
- 依赖变更：`gradle/libs.versions.toml` 需新增 `androidx.customview`（当前无此依赖）。
- 节点描述需读出标题、时间、状态（已过/进行中/未到），文案走 `strings.xml`（FC-003）。
- 视口外的块**不产生**节点（否则屏幕阅读器会念出看不见的内容）。

### AD-06　刷新必须收敛（反 D17 / D10）

**决策**：全组件**只有一个**刷新入口 `requestRefreshIfChanged()`，内部比对「渲染签名」后才 `invalidate()`。

渲染签名 = `(取整到分钟的当前时刻, 各日程三态快照, 选中/编辑态, 视口尺寸)`。签名不变则完全不刷新。

当前时间线的 30 秒定时器：
- `Handler.postDelayed` 自循环，且**在 `onDetachedFromWindow` / 不可见时 `removeCallbacks`** —— 这是 E10（页面刚开就关，无残留任务）与 Q6（无内存增长）的落点。
- 定时器只在「需要显示当前时间线」时运行（今天 / 强制显示 / 未配置关闭）。

**明令禁止**（代码走查项，违反即 T6 不过）：
- 任何 `postInvalidateDelayed` 形式的循环；
- 任何无限 `ValueAnimator`；
- `onDraw` 内触发状态变更导致的重入。

**依据**：D17、D10、§12.1「长时间停留 1 小时耗电无明显上升」、Q5。

### AD-07　增量更新与编辑态存活（易被忽略的正确性核心）

**决策**：`submitEvents(list)` 走按 `id` 的 diff，不做全量重建。

**滚动位置保持（FD-003 / AC-06「无闪烁、无跳变、无重排」）**：更新前记录**滚动锚点** = 视口顶部那条日程的 `id` + 其顶边相对视口顶部的像素偏移。更新后若锚点日程仍存在则精确还原；否则退化为保持绝对像素偏移。

**编辑态三条数据变更规则（E20 / E21 / E28）**——这是 D3「取消不发数据变更」的延伸，必须显式实现为决策表：

| 情况 | 行为 | 是否发事件 |
|---|---|---|
| E20 被编辑的日程已不在新列表中 | 静默取消编辑态，丢弃未提交改动 | **不发任何数据变更事件** |
| E21 新列表不含被编辑日程（无关变更） | **保持编辑态不动**，用户操作不被打断 | 不发 |
| E28 被编辑的日程本身被业务方改了 | 保持编辑态，置 `conflict` 标记；用户完成时在 `onEventModified` 携带该标记，由业务方决定是否覆盖 | 完成时才发 |

**注意 E20**：编辑态中被删除的日程**不能**退化为「新建」或触发修改事件，否则就是 D3 的变体事故。

### AD-08　四层定制入口（§10.2 / D7 / US11）

全部通过 `library` 公开 API 提供，**业务方不得需要修改或复制组件源码**（D7 硬性要求）。

| 层 | 扩展点 | 优先级 | 备注 |
|---|---|:--:|---|
| 一、参数 | `TimelineConfig` data class + `<declare-styleable>` XML 属性，**两者一一对应** | P0 | FC-005 要求界面配置与代码配置效果完全一致 |
| 二、网格绘制 | `fun interface GridPainter` | P1 | 替换刻度线/标签/当前时间线画法 |
| 三、块内容 | `fun interface EventBlockPainter` | P0 | 替换日程块内部显示，US11 落点 |
| 四、交互接管 | `EditController` | P1 | 业务方接管新建流程，自行弹底部面板 |

第三层是业务方诉求最集中的一层（US11），实现时需保证默认实现的绘制成本足够低（R4：业务方自定义样式导致掉帧）。

### AD-09　配置与样式的单一来源（FC-001/002/003、T3、D8）

- **尺寸**：全部来自 `R.dimen.*` → `Dimens` 对象，组件内**无任何尺寸字面量**（FC-001 / T3）。
- **颜色**：17 项语义色项在 `attrs.xml` 声明 + `colors.xml`（浅）/ `values-night/colors.xml`（深）取值。§7.3 有强制要求：组件内**不得出现色值字面量**，深色适配**仅靠替换资源、不得写分支逻辑**。这是代码走查项。
- **文案**：全部 `strings.xml`，无硬编码（FC-003 / D13）。

**阻塞依赖**：17 个色项当前**没有取值**（M0 未完成）。见 §7 开放问题 Q1。

### AD-10　RTL 从第一天内置（E16）

几何计算产出**逻辑左/右**，绘制入口按 `layoutDirection` 统一映射；RTL 下时间轴区域位于右侧。

**禁止**「先做 LTR，后期再镜像」——AD-01 的绘制代码里若散落绝对 x 坐标，后期改造成本极高。AD-03 输出的 `PlacedBlock` 应携带逻辑方向字段而非绝对像素。

### AD-11　状态保存（D12 / E9 / Q9）

`onSaveInstanceState` 存：查看日期、自身滚动模式下的 `scrollOffset`、选中/编辑态标识。**不存业务数据**（L8 组件不做持久化）。

外部滚动模式下只存查看日期，滚动位置交外层容器（§8.6 明确要求）。

### AD-12　语义色项取值：Material 3 baseline 作工程占位　【执行期决策】

**背景**：PRD §7.3 只定义「有哪些语义色项」，**具体色值由 M0 设计交付**，而 M0 尚未完成。§16.2 明确「M0 未完成不得进入 M1」。

**决策**：不等 M0，用 **Material 3 baseline 调色板**给出一套完整可用的浅色/深色取值作为工程占位，并明确标注非终稿。

**理由（为什么可以不等）**：
- AD-09 的整个架构目的就是「色值集中在 `colors.xml` / `values-night/colors.xml` 两处」，定稿时**只改这两份文件的取值即可，组件代码一行不动**。语义色项设计本身就是为降低这种返工成本。
- app 主题已是 `Theme.Material3.DayNight.NoActionBar`，取 M3 同源色值在视觉上与宿主应用一致，不突兀。
- 不取值则任何配色相关代码都开不了工，M1–M2 全部停摆。

**影响与遗留风险**：
- **R10 风险依然成立**：定稿后需复查深色下的对比度（Q7/UF-004）。当前深色取值未做对比度实测。
- 定稿动作：替换两份 `colors.xml` 中的 17 个取值 → 跑 §14.3 Q7 → 更新本条状态。

**验证**：已落地 `values/colors.xml` 与 `values-night/colors.xml`，两者色项名逐一对齐（缺一即运行期崩溃）。

### AD-13　core-ktx 降级至 1.18.0　【执行期决策】

**背景（这是一个先前就存在的工程缺陷，非本次改动引入）**：`gradle/libs.versions.toml` 中 `coreKtx = "1.19.1"`，而 `core-ktx 1.19.x` 要求 **compileSdk ≥ 37 且 AGP ≥ 9.1.0**。本工程为 compileSdk 36 / AGP 8.13.2，因此 `:app:assembleDebug` 在 `checkDebugAarMetadata` 阶段直接失败。

**两个选项**：

| 选项 | 代价 |
|---|---|
| A. 升级 compileSdk 到 37 + AGP 9.1+ | 需连带 Gradle 9.x wrapper、Kotlin 版本、可能重写 build 脚本 DSL；与 §2.2 记录的既定技术栈全面冲突 |
| B. 降级 core-ktx 到兼容 compileSdk 36 的最高版本 | 仅改一行版本号，不动工具链 |

**决策：选 B**，`coreKtx = "1.18.0"`。

**影响**：保留 AGP 8.13.2 / Kotlin 2.0.21 / Gradle 8.13 不变；未来若整体升级工具链，可再把 core-ktx 升回 1.19.x。

### AD-15　无量纲比值不得放进 dimens.xml　【执行期决策】

**背景（真实事故）**：`day_timeline_drag_threshold_ratio` 曾声明为
`<item name="..." format="float" type="dimen">0.3</item>`。

AAPT2 中 `format` **覆盖** `type`，因此该值被编译为 `TYPE_FLOAT(0x4)`；
而 `Resources.getDimension()` 只接受 `TYPE_DIMENSION(0x5)`，
真机启动即抛 `Resources$NotFoundException: Resource ID #0x… type #0x4 is not valid`，
崩溃点在 `DayTimelineView` 构造函数内，App 直接起不来。

**为什么 JVM 单测、lint、覆盖率门禁全部漏掉**：三者都不加载 Android 资源，
「资源类型与读取方式是否匹配」对它们完全不可见。

**决策**：
1. 从 `dimens.xml` 移除该条目，改用代码常量 `Dimens.DEFAULT_DRAG_THRESHOLD_RATIO = 0.3f`。
   **无量纲比值本来就不是尺寸**，放进 dimen 目录本身就是错的落位。
2. 需要业务方覆盖时走 `<attr format="float">` + `TypedArray.getFloat()`，
   这是 API 1 起就支持的安全路径。
3. 在 `dimens.xml` 内留下警示注释，说明这条坑，避免复发。

**替代方案与否决理由**：改为 `<integer>` 千分比（`300` / 1000f）也能解决，
但引入一个语义别扭的资源名，且与「XML 侧用 float attr」的表达方式不一致。

### AD-16　单位换算以资源声明为准，禁止二次 applyDimension　【执行期决策】

**背景（与 AD-15 同一次排查发现）**：`Dimens.resolve` 原先写的是
```kotlin
TypedValue.applyDimension(COMPLEX_UNIT_DIP, res.getDimension(id), res.displayMetrics)
```

`Resources.getDimension()` 返回的**已经是**按资源自身单位换算好的 px
（`12sp` → `12 × scaledDensity`）。再套一层 `COMPLEX_UNIT_DIP` 会把 density 乘第二次：
在 density=3 的设备上 `12sp` 变成 108px 而不是 36px，**字号放大三倍**。

**决策**：删除 `dp()` / `sp()` 两个辅助函数，统一为
`dim(id) = res.getDimension(id).toInt()`。单位由资源声明本身（`12dp` / `12sp`）决定，
代码不再、也不应该区分——这也顺带消除了 `sp()` 这个从未被调用的死代码（T5）。

**遗留**：`Dimens` 中所有 dp 语义尺寸不受影响（它们本来也只被乘一次 density）。

### AD-17　XML 配置经 TypedArray 读取，且 setConfig 采用合并语义　【执行期决策】

**背景**：`attrs.xml` 声明了 53 项属性，但**从未有任何 `obtainStyledAttributes` 调用**，
即 §10.1 的「界面配置」侧完全没实现——业务方写 `app:dtHourHeight="80dp"`
不报错也不生效，是典型的静默失败（FC-005 的一半缺失）。

**决策 1 —— 读取**：
新增 `internal/ConfigFromAttrs.read(context, attrs)`，与 `TimelineConfig` 字段一一对应。
**每个字段都以 `TypedArray.hasValue()` 把关**，未书写的属性保持 `null`，
因为 `null` 才表示「用资源默认值」，落成 `0`/`false` 会被当成业务方显式要求取 0。

**决策 2 —— 构造顺序**：Kotlin 属性初始化器按声明顺序执行，`dimens` 的初值
在 `init` 块**之前**求出，拿不到 attrs。因此在 `init` 中读一次 XML 配置后
整体重算（`rebindDerivedState()`）。构造只发生一次，多这一次开销可忽略。

**决策 3 —— `setConfig` 改为合并语义**：原先是整体替换。实现 XML 配置后立刻暴露一个问题：
业务方在 XML 打底之后再调 `setConfig(TimelineConfig(hourHeight = …))`，
**XML 里其它字段会被悄悄清成 null 退回默认值**，表现为「XML 配置好像没生效」且无任何报错。
因此新增 `TimelineConfig.mergedWith(other)`——`other` 的非 null 字段覆盖，其余保留。
这样「XML 打底 + 代码微调」自然成立，也符合 FC-005「两侧一一对应、效果完全一致」。

**未映射的属性**：`dtShowEditActions` / `dtShowDeleteAction` 已从 `attrs.xml` 移除，
`TimelineConfig.showEditActions` / `showDeleteAction` 同步移除。
原因是内置「完成/取消/删除」按钮尚未实现（§7.7）——声明一个不生效的属性，
比不声明更糟。实现按钮时一并加回。

### AD-18　补真机测试作为「资源契约」的守门人　【执行期决策】

**决策**：新增仪器测试文件，专治「JVM 单测看不见」的那一类缺陷：

| 文件 | 作用 |
|---|---|
| `ResourceContractTest` | 遍历**全部** `R.dimen` / `R.integer` / `R.color`，用生产代码同样的方式读取；任何类型不匹配立刻失败。另断言 17 项语义色项齐全、字号等于 12sp 换算值 |
| `DayTimelineViewTest` | 构造视图并走完测量/绘制；断言代码配置生效、`setConfig` 为合并语义、热区 ≥48dp、外部滚动模式高度 = 全天内容高度、极小格高被兜底 |
| `DayTimelineViewBehaviorTest` | 全部文字 Paint 的 `textSize` 非零、自身模式确实可滚、偏移钳制、绘制含多种颜色、脏数据容错 |

**这是对 AD-15 那次事故的直接回应**：单纯修好那一行不够，
必须让「同类错误无法再次通过门禁」。这三份用例均在 `connectedDebugAndroidTest` 中执行。

### AD-19　块内文字必须显式设置 textSize；窄列需省略号截断　【执行期决策】

**背景（真机可见缺陷）**：`Theme.Paints` 中 `blockText` 写成
`Paint(Paint.ANTI_ALIAS_FLAG)`，**没有设置 `textSize`**。
`Paint` 的默认值是 **12 个原始像素**（不是 12sp），因此在 density=3 的设备上
块内文字只有 12px 高，真机上「基本看不见」。

`axisLabel` / `nowLabel` 都设了 `textSize`，唯独漏了 `blockText`——
这类"漏一行"的缺陷编译期与 lint 都无法发现。

**决策**：
1. 新增 `day_timeline_block_text_size`（12sp），并显式写入 `blockText.textSize`。
   PRD §7.2 未规定块内字号，取与轴标签一致的 12sp，同时作为可配置项
   （`dtBlockTextSize`）暴露，避免再次硬编码（FC-001）。
2. **顺带修掉文字溢出**：原绘制直接 `drawText` 不截断，重叠分栏后窄列
   （可能只有 1/3 宽）里文字会直接压在相邻块上。改为按可用宽度二分查找截断
   并加省略号（PRD §7.4「超出以省略号截断」），单次 O(log n) 次 `measureText`。

**回归防线**：`DayTimelineViewBehaviorTest.everyTextPaintHasSaneTextSize`
用反射**遍历全部 Paint**，找出名字含 label/text 的并断言 `textSize >= 8px`。
这样下次新增 Paint 漏设同样会被抓到，而不是只守住 `blockText` 一个。

### AD-20　自身滚动必须真正消费手势　【执行期决策】

**背景（真机可见缺陷）**：`GestureArbiter` 会正确判定出 `Intent.Scroll`，
但 `onTouchEvent` 里对该分支只写了 `return false`——**拖拽滚动根本没实现**。
`scrollOffset` 只会被 `scrollToMinute` 和边缘自动滚动改动，
结果就是只能看到一屏内容。

根因是 M4 只实现了"手势判定"却没接上"手势执行"，判定与执行脱节。

**决策**：补齐 FI-001 的完整链路：
- `ACTION_DOWN`：记录 `lastScrollTouchY`，创建 `VelocityTracker`
- `Intent.Scroll` + `SELF` 模式 → `dragScroll()` 按**增量**更新 `scrollOffset`
  （用增量而非绝对值，丢帧时也不会跳位），并 `requestDisallowInterceptTouchEvent(true)`
  拦住下拉刷新（E29）
- `ACTION_UP` → `endScrollGesture()` 用 `OverScroller.fling` 做惯性
  （§8.5「惯性跟随系统原生手感」），随后解除父容器拦截
- 边界**直接钳制**而非 overscroll，因为 §8.5 明确「边界回弹关闭」
- `ACTION_CANCEL` 与 `onDetachedFromWindow` 回收 `VelocityTracker`（Q6）

**外部滚动模式保持不变**：该分支仍 `return false` 把手势让给外层（§8.6 / AD-04）。
因为外部模式下组件高度等于全天内容高度，`maxScroll()` 天然为 0，组件本身不会滚。

**回归防线**：`DayTimelineViewBehaviorTest` 覆盖滚动偏移钳制、跳转到首尾、
外部模式自身不滚，以及"绘制结果含多种颜色"（防止改坏绘制却看不出来）。

### AD-21　惯性动画必须与读取的轴一致　【执行期决策】

**背景（真机可见缺陷）**：滑动可用，但**一松手就弹回原处**——
滚到 17 点附近立刻被弹回 00~09。

根因是 `fling()` / `startScroll()` 的参数填到了 **X 轴**，而 `computeScroll()` 读的是
**`scroller.currY`**：

```kotlin
scroller.fling(scrollOffset, 0, 0, velocityY, 0, max, 0, 0)
//        ↑startX      ↑startY ↑vx   ↑vy      ↑minX ↑maxX ↑minY ↑maxY
```

`currY` 因此恒为 0，惯性期间 `computeScroll()` 每帧把 `scrollOffset` 写回 0。
拖拽阶段走的是 `dragScroll()`（直接改 `scrollOffset`），所以拖得动、一抬手就回弹。

**决策**：
1. `fling` / `startScroll` / `computeScroll` **统一使用 Y 轴**；
   `scrollOffset` 本就是纵向偏移，Y 轴才是唯一自洽的选择。
2. 边界用 `minY=0 / maxY=max` 直接钳制，不启用 overscroll（§8.5「边界回弹关闭」）。
3. **只有判定为滚动的抬手才启动惯性**。原先每次 `ACTION_UP` 都会走
   `endScrollGesture()`，点击 / 长按 / 编辑拖拽也会碰 scroller，
   容易与编辑态绘制时序打架。现在按 `Intent.Scroll` 把关。

**教训**：`OverScroller` 有 X/Y 两套平行的参数位，填错轴**编译不会报错**、
运行也只是"不弹了"而非崩溃，属于极易漏过的一类。
`startScroll` 同理，此前 `scrollToMinute(smooth=true)` 也一直静默失效。

**回归防线**：`DayTimelineViewBehaviorTest` 新增两条：
- `smoothScrollDoesNotSnapBack`：逐帧推进动画，断言偏移**单调不减**（不回退）
  且最终离开起点
- `scrollOffsetStaysPutAfterGesture`：不在动画中时反复调 `computeScroll()` 不得改动偏移




**决策**：新增仪器测试文件，专治「JVM 单测看不见」的那一类缺陷：
| 文件 | 作用 |
|---|---|
| `ResourceContractTest` | 遍历**全部** `R.dimen` / `R.integer` / `R.color`，用生产代码同样的方式读取；任何类型不匹配立刻失败。另断言 17 项语义色项齐全、字号等于 12sp 换算值 |
| `DayTimelineViewTest` | 构造视图并走完测量/绘制；断言代码配置生效、`setConfig` 为合并语义、热区 ≥48dp、外部滚动模式高度 = 全天内容高度、极小格高被兜底 |
| `DayTimelineViewBehaviorTest` | 全部文字 Paint 的 textSize 非零、自身模式确实可滚、偏移钳制、绘制含多种颜色、脏数据容错 |

**决策**：新增两个仪器测试文件，它们专治「JVM 单测看不见」的那一类缺陷：

| 文件 | 作用 |
|---|---|
| `ResourceContractTest` | 遍历**全部** `R.dimen` / `R.integer` / `R.color`，用生产代码同样的方式读取；任何类型不匹配立刻失败。另断言 17 项语义色项齐全、字号等于 12sp 换算值 |
| `DayTimelineViewTest` | 构造视图并走完测量/绘制；断言代码配置生效、`setConfig` 为合并语义、热区 ≥48dp、外部滚动模式高度 = 全天内容高度、极小格高被兜底 |

**这是对 AD-15 那次事故的直接回应**：单纯修好那一行不够，
必须让「同类错误无法再次通过门禁」。



**背景**：首版把 `GridContext` / `BlockContext` 写成不可变 `data class`，在 `onDraw` 中每帧构造。`lint` 的 `DrawAllocation` 报为错误（我们配置了 `warningsAsErrors`，符合 T6）。

**问题实质**：200 条日程时每帧要 new 200 个 `BlockContext`，与 §12.1「增量更新 100 条 ≤ 16ms」「滚动掉帧率 ≤ 3%」直接冲突。这是真实的性能缺陷，不是 lint 误报。

**决策**：改为**字段可变**的普通类，组件内各持一份并在每帧改写复用；并在 KDoc 中明确要求实现方**不得长期持有 context 引用**。

**代价**：`data class` 的 `equals`/`copy` 便利性丢失。这是为性能必须付的代价，属有意取舍。

**延伸**：`GridPainter.Paints` / `EventBlockPainter.Paints` 包装同样提升为字段，消除最后两处每帧分配。

---

## 4. 需求覆盖对照

| 需求组 | 主要实现位置 | 验证方式 | 里程碑 |
|---|---|---|---|
| FR-001~004, 008, 014 | `GridPainter` 默认实现 | 单元测试 + 截图走查 | M2 |
| FR-005~007 | AD-03 + `Geometry` | **U1–U16** | M2 |
| FR-009~013 | `TimeStateResolver` + `Theme` | E30、单元测试 | M2 / M5 |
| FI-001, FI-002 | AD-04 | E29、E12 | M3 |
| FI-003~005 | 手势判定 | 场景 A/B | M4 |
| FI-006, FI-007 | `Time` 吸附 + 拖拽 | Q11 对称性测试、场景 C/D | M4 |
| FI-008~011 | 编辑态状态机 | **Q1 专项**、场景 B/E | M4 |
| FI-012, FI-013 | 滚动定位 API | 单元测试 | M3 |
| FI-014 | 边缘自动滚动（跟随块而非手指） | 场景 C 跨屏长日程 | M4 |
| FI-015 | 命中测试扩大热区 | Q10 走查 | M4 |
| FI-016 | AD-05 | Q8 真机 | M5 |
| FD-001~007 | `api/` + AD-07 | E1–E8、场景 F/G | M3 |
| FC-001~006 | AD-08/AD-09 | 单元测试 + 走查 | M5 |
| Q1–Q11 | 全局 | §14.3 底线验收 | M4 / M5 |
| T1–T7 | §5 基础设施 | §12.4 门禁 | M1 / M6 |

---

## 5. 测试与质量门禁落地

### 5.1 三层测试策略

| 层 | 范围 | 工具 | 依赖设备 |
|---|---|---|---|
| L1 纯 JVM 单测 | `core/` 全部 + `internal/` 的 diff 与状态机 | JUnit（已在 catalog） | 否 |
| L2 仪器测试 | View 的测量/绘制/手势/无障碍 | `androidx.test` + Espresso（已在 catalog） | 是 |
| L3 性能与真机走查 | §12.1 指标、Q1–Q11 底线 | 手测 + 内存/掉帧观测 | 是 |

**L1 是覆盖率达标的主力**：`core/` 纯 Kotlin 是最容易也最该测的部分（T2 就是为此设的技术基线）。核心逻辑 ≥ 90% / 全库 ≥ 75%（T4）应主要由 L1 达成，而不是靠仪器测试堆数字。

**L2 现状**：仓库仅有模板生成的 `Example*Test`，**无任何真实测试**。M1 需确认 `androidx.test` 版本可用，M2 起开始写。

### 5.2 门禁工具选型（当前全部缺失，M1 一次性补齐）

| 门禁（§12.4） | 现状 | 建议方案 |
|---|---|---|
| 静态代码检查 0 严重 | 无 | detekt（比 ktlint 覆盖面更广，且能顺带做部分死代码检查） |
| 代码风格检查 0 违规 | 仅 `kotlin.code.style=official` | detekt 的 formatting 规则集 |
| 平台规范检查 0 错 0 警 | 有默认 `lint`，无配置 | 为 `library` 配 `lint { }`，AAR 对外暴露的 API 需开 `UnstableApiUsage` 等相关检查 |
| 覆盖率 核心 ≥90% / 全库 ≥75% | 无 | JaCoCo + 阈值任务，**不达标即构建失败** |
| 死代码 0 处 | 无 | detekt `UnusedPrivate*` + 对 `android.view` XML 反射实例化的类做白名单（见下） |
| 无高危漏洞 | 无 | `dependencyCheck` 或 Renovate/Dependabot 告警 |
| UI 自动化冒烟 | 无 | L2 仪器测试跑关键路径 |
| 文档无待办标记 | 无 | CI 中 grep `TODO`/`FIXME`（仅 release 分支阻断） |

**死代码检查的现实难点**：Android 框架通过 XML 反射实例化 View，自定义属性/序列化场景也会反射调用，detekt 会误报。必须维护一份**显式豁免清单**，而不是全局关闭规则——豁免清单本身要进代码走查。**这条很容易被做成「关掉检查」，务必避免**，T5 要求的是「0 处」且「纳入自动化阻断」。

**`core/` 纯度检查**：建议加一个轻量单元测试或自定义 lint，扫描 `core/` 下的源文件是否 import 了 `android.*`，防止 T2 纪律被无意破坏。

### 5.3 CI

仓库**当前无 CI**（无 `.github/`），无 pre-commit 钩子。建议 GitHub Actions，PR 触发：`detekt` → `:library:assembleDebug` → `test`（L1）→ 覆盖率阈值 → `lint`。R8 验证（M6）需另加一个 `minifyEnabled = true` 的消费端变体跑仪器测试。

---

## 6. 任务拆解

沿用 PRD §16.2 的里程碑与人天。**每项任务的「覆盖」列标注对应 PRD 需求编号**，便于验收时反查。

### M0　设计定稿（3 人天，PRD 已有）— **当前唯一硬阻塞**

| # | 任务 | 交付物 | 覆盖 |
|---|---|---|---|
| M0-1 | 17 项语义色项取值表 | 浅色 + 深色两套色值表 | §7.3 / FC-002 |
| M0-2 | 交互标注 | 状态流转图、手柄与热区标注 | §7.7 / §8.1 |
| M0-3 | 切图与字体资源 | 字体规格、图标 | UF-001 |

**出口**：§7.3 全部 17 项有色值定稿。**未完成不得进入 M1**（PRD 明确；R10：用占位色开发必然返工）。

### M1　工程基线（2 人天）

| # | 任务 | 覆盖 | 依赖 |
|---|---|---|---|
| M1-1 | `:app` 增加 `implementation(project(":library"))` | §2.3 | — |
| M1-2 | `library` 建立 `res/` 骨架：`values/`（dimens/colors/attrs/strings）、`values-night/` | AD-09 | M0-1 |
| M1-3 | 引入 detekt + 格式化规则，接入构建 | T6 | — |
| M1-4 | 引入 JaCoCo 与覆盖率阈值任务 | T4 | — |
| M1-5 | 死代码检查 + 豁免清单机制 | T5 | M1-3 |
| M1-6 | 配置 `lint`，接入 CI（detekt → build → test → 覆盖率 → lint） | §12.4 | M1-3/4/5 |
| M1-7 | `core/` 纯度检查（禁 `android.*` import） | T2 | — |
| M1-8 | **验证 AD-02 的 R9 结论**（minSdk 23 + value class 最小样例），据此关闭 R9 并回改 PRD §18 | D18 / R9 | — |
| M1-9 | 新增 `androidx.customview` 依赖 | AD-05 | — |

**出口**：§12.4 全部门禁可跑通并能阻断。

### M2　静态呈现（5 人天）

| # | 任务 | 覆盖 | 依赖 |
|---|---|---|---|
| M2-1 | `MinuteOfDay` 值类型 + `Time` 吸附（**上下对称，D11**） | D18/D19/D11/AD-02 | M1-7 |
| M2-2 | `EventSanitizer` 脏数据修正 + 异常收集 | §9.3 / E3–E7 / Q4 | M2-1 |
| M2-3 | `TimeStateResolver` 三态推导 + 覆盖规则 | §9.4 / FR-009 | M2-1 |
| M2-4 | `Geometry` 分钟↔像素换算 + 尺寸非负守卫 | D4 / Q2 / E17 | M2-1 |
| M2-5 | `OverlapLayoutEngine`（**先简单版**） | D1 / §7.9 / FR-006 | M2-2 |
| M2-6 | **U1–U16 逐条单测** | §14.2 | M2-5 |
| M2-7 | U12（200 条全同时）性能实测，不达标则上 O(n log C) 优化 | §12.1 / R1 | M2-6 |
| M2-8 | `GridPainter` 默认实现：25 条刻度线 + 25 个时间标签 + 上下留白 | FR-001/002/003/014 | M2-4 |
| M2-9 | 当前时间线绘制 | FR-010 | M2-8 |
| M2-10 | `EventBlockPainter` 默认实现 + 三态样式 | FR-005/007/009 | M2-3, M2-5 |

**出口**：U1–U16 全部通过；无重叠、无错位、无尺寸为负。

### M3　数据与滚动（4 人天）

| # | 任务 | 覆盖 | 依赖 |
|---|---|---|---|
| M3-1 | `api/` 契约：`TimelineEvent` / `TimelineListener` / `DataIssue` | FD-001 / §11 | M2-1 |
| M3-2 | 提交列表 / 单条更新 / 单条删除 / 切换日期 | FD-002/004/005 / §11.1 | M3-1 |
| M3-3 | 增量 diff + **滚动锚点保持** | FD-002/003 / AC-06 | M3-2 |
| M3-4 | 刷新收敛 `requestRefreshIfChanged` + 渲染签名 | D17 / D10 / Q5 | M2-10 |
| M3-5 | 30s 定时器 + 生命周期摘除 | FR-009 / E10 / Q6 | M3-4 |
| M3-6 | 自身滚动模式（`OverScroller` + `GestureDetector`） | FI-001 / §8.5 | M3-4 |
| M3-7 | 外部滚动模式 + 高度约定 | FI-002 / §8.6 / E12 / E29 | M3-6 |
| M3-8 | 首次定位当前时间 / 跳转到指定时刻 | FI-012/013 | M3-6 |
| M3-9 | 200 条流畅度与增量耗时实测 | §12.1 / R3 | M3-3 |

**出口**：200 条日程流畅滚动；E12 / E13 / E29 通过；增量更新 100 条 ≤ 16ms。

### M4　交互闭环（6 人天）

| # | 任务 | 覆盖 | 依赖 |
|---|---|---|---|
| M4-1 | 手势判定状态机（阈值 = 标准 × 0.3；点击/长按/滚动三者互斥） | §8.2 | M3-6 |
| M4-2 | 空白点击 → 新建编辑态（默认 1 小时） | FI-005 / US4 | M4-1 |
| M4-3 | 长按 → 编辑态；长按空白不进入编辑 | FI-004 / §8.2 | M4-1 |
| M4-4 | 拖拽移动 + 吸附 + 全天范围钳制 | FI-006 / Q11 | M2-1, M4-1 |
| M4-5 | 上下手柄调整时长（**视觉 6dp / 热区 48dp**） | FI-007 / FI-015 / Q10 | M4-4 |
| M4-6 | 完成 / 取消 / 删除 + 二次确认 | FI-008/009/011 / §7.7 | M4-2 |
| M4-7 | **Q1 专项：取消路径零数据变更事件**（D3，最高优先级回归） | D3 / Q1 | M4-6 |
| M4-8 | 边缘自动滚动（跟随**块**而非手指） | FI-014 / §8.4 | M4-4 |
| M4-9 | 编辑态 × 数据变更决策表（E20/E21/E28） | E20/E21/E28 | M3-3 |
| M4-10 | 高度不足 48dp 的块自动扩展热区 | FI-015 | M4-5 |

**出口**：场景 B / C / D / E 全通过；Q1、Q3 达标；取消路径单测覆盖全部入口。

### M5　打磨（5 人天）

| # | 任务 | 覆盖 | 依赖 |
|---|---|---|---|
| M5-1 | 全量配置项 + XML attrs（与代码配置一一对应） | FC-001/003/005 / §10.1 | M4 |
| M5-2 | 深色模式 + **存活期切换即时重绘** | FR-012 / E30 / Q7 | M5-1, M0-1 |
| M5-3 | 多语言 + 存活期切换 | D13 / E31 | M5-1 |
| M5-4 | 时间格式 24/12/跟随系统 | FC-004 / E15 | M5-1 |
| M5-5 | RTL 镜像（AD-10 内建，此处仅验证） | E16 / §12.2 | M2 起内置 |
| M5-6 | **无障碍虚拟视图** | Q8 / UF-002/003 / FI-016 | AD-05, M1-9 |
| M5-7 | 字体放大 200% + 标签自动降密度 | E14 | M2-8 |
| M5-8 | 状态保存与恢复 | D12 / E9 / Q9 | M3-6 |
| M5-9 | 折叠屏 / 分屏 / 缺口避让 | E12 / E22 | M3-7 |
| M5-10 | 走查确认：无硬编码尺寸/颜色/文案 | T3 / D8 | 全程 |
| M5-11 | 调试期与发布期全量禁用日志/上报 | L8 / §15 口径 | M6 |

**出口**：§7 / §12 全部达标；Q7–Q10 达标。**R7：M5-6 不可压缩**。

### M6　发布（2 人天）

| # | 任务 | 交付物 |
|---|---|---|
| M6-1 | 示例应用：真实重叠数据、配置面板、深色切换、全部交互路径 | DL-02 |
| M6-2 | 快速上手 / 完整能力 / 主题定制 / 交互说明 四份文档 | DL-03~DL-06 |
| M6-3 | **R8 混淆验证**：`consumer-rules.pro` 补齐 + `minifyEnabled=true` 消费端跑仪器测试 | T7 / §12.5 |
| M6-4 | Apache-2.0 许可与版权人信息 | DL-07 |
| M6-5 | 变更记录 | DL-08 |
| M6-6 | 质量报告：覆盖率实测、性能实测、兼容机型清单 | DL-09 |

**出口**：§14 全部验收项通过。

### 6.1 排期与依赖观察

- **关键路径**：M0 → M1 → M2 → M3 → M4 → M5 → M6，严格串行（PRD 已定为串行）。
- **M0 是硬阻塞**：色项未定稿，M1-2 无法完成，M2 的默认配色也无法开工。**当前实际卡在这一步。**
- **M2 与 M5 的工作量风险最高**：M2 因全量自绘而比常规 View 组件重；M5 因无障碍（AD-05 自建虚拟视图）而重。PRD 给 M2 5 天、M5 5 天，**建议 M1 结束后按实际速率复核这两个估算**。
- **不可压缩项**：M5-6 无障碍（R7）、M4-7 取消语义（D3）。排期压缩时优先砍 M1 的工具选型讨论与文档，不要砍这两项。

---

## 7. 开放问题（需拍板）

| # | 问题 | 影响 | 建议 | 阻塞性 |
|---|---|---|---|:--:|
| Q1 | 17 项语义色项无取值（M0 未完成） | 无法开发任何配色相关代码 | 优先完成 M0 | **阻塞** |
| Q2 | 「零第三方依赖」未区分**运行时**与**测试期**。Robolectric / MockK 可让 View 层可单测，但会突破字面表述 | 影响测试策略与覆盖率达成方式 | 建议改述为「**运行时**零非 AndroidX 第三方依赖；测试期允许」 | 高 |
| Q3 | K10 写「零第三方依赖」，§12.2 却允许 AndroidX 官方库。措辞冲突 | 影响依赖审查口径 | 统一为「零非 AndroidX 第三方运行时依赖」 | 中 |
| Q4 | 风险 **R9**（minSdk 与 D18 冲突） | 本文 AD-02 给出结论「不冲突，`value class` 无运行时依赖」 | M1-8 实测后关闭 R9，并同步更新 PRD §18 | 中 |
| Q5 | PRD §16.2 有**重复段落**：第 1064 行与 1066 行两次出现「排期为粗略预估…」，第 1066 行是冗余副本 | 文档质量 | 删掉第 1066 行 | 低 |
| Q6 | PRD §12.2 原文「Android 7.0」已按 `minSdk = 23` 修正为「Android 6.0（API 23）」，但 §18 的 R9 仍以旧前提描述 | R9 结论一旦采纳，R9 整条应重写而非关闭 | 与 Q4 一并处理 | 中 |
| Q7 | 对外 API 是否需要 Java 调用方友好门面（`value class` 擦除问题） | 影响 `api/` 设计 | 建议提供（`api/` 层加静态工厂），成本低 | 中 |
| Q8 | L2 仪器测试的 `androidx.test` 版本当前未验证可用 | 影响 M2 起的测试排期 | M1 验证 | 低 |

---

## 8. 变更记录

| 版本 | 日期 | 变更内容 | 修订人 |
|---|---|---|---|
| v0.1 | 2026-09-30 | 初稿。基于 PRD v1.1 输出架构决策 AD-01~AD-11、M0–M6 任务拆解、测试与门禁落地方案、8 项开放问题。给出 R9 的技术结论（`value class` 使 minSdk 23 与 D18 兼容）。 | — |
| v0.2 | 2026-09-30 | 开工后补充执行期决策 AD-12（色项占位）、AD-13（core-ktx 降级）、AD-14（绘制上下文复用），并回写 §9 实施进度。 | — |
| v0.3 | 2026-10-08 | 真机崩溃排查后补 AD-15~AD-18：format=float 导致 TYPE_FLOAT 与 getDimension 不兼容、单位二次换算致字号放大三倍、XML 配置此前从未被读取且 setConfig 应为合并语义、补真机资源契约测试。 | — |
| v0.4 | 2026-10-08 | 真机暴露两个新缺陷后补 AD-19/AD-20：块内文字漏设 textSize 用了 Paint 默认的 12 原始像素；以及自身滚动模式**根本没实现拖拽滚动**（判定有、执行无）。同时补窄列文字省略号截断与对应真机回归测试。 | — |
| v0.5 | 2026-10-08 | 真机复测「滑动后回弹」后补 AD-21：`fling`/`startScroll` 参数填到了 X 轴而 `computeScroll` 读 `currY`，`currY` 恒为 0 导致惯性期间每帧写回 0。统一到 Y 轴并按 Intent 把关是否启动惯性。 | — |

---

## 9. 实施进度回写

### 9.1 当前状态

| 里程碑 | 状态 | 说明 |
|---|:--:|---|
| M0 设计定稿 | **部分** | 语义色项**结构**与 17 项取值已就位（Material 3 baseline 占位，AD-12）；交互标注、切图、字体资源仍缺，属设计职责 |
| M1 工程基线 | **部分** | `:app`→`:library` 接线、res 骨架、`androidx.customview` 依赖、lint 严格配置已完成；**JaCoCo 覆盖率阈值、detekt/死代码检查、CI 尚未建立** |
| M2 静态呈现 | **大部分** | `core/` 全部算法 + U1–U16 逐条验收（20 个用例）已完成；网格与日程块绘制已完成 |
| M3 数据与滚动 | **部分** | 数据契约、提交/单条更新/单条删除/切日期/指定当前时间、增量 diff + 滚动锚点、刷新收敛、30 秒定时器与生命周期摘除已完成；**两种滚动模式仅自身模式有雏形，外部滚动模式待补** |
| M4 交互闭环 | **未开始** | 手势状态机、编辑态、拖拽吸附、手柄、完成/取消/删除待做 |
| M5 打磨 | **部分** | 字体放大标签降密度、状态保存（`onSaveInstanceState`）已做；深色/多语言/RTL/无障碍虚拟视图待做 |
| M6 发布 | **未开始** | ProGuard 规则、混淆验证、四份交付文档待做 |

### 9.2 门禁现状

| 门禁（§12.4） | 状态 | 证据 |
|---|:--:|---|
| 核心逻辑单元测试覆盖率 ≥ 90% | 未测量 | 尚无 JaCoCo 配置（M1-4 待做） |
| 平台规范检查 0 错误 | **达成** | `:library:lintDebug` 通过，0 error / 0 warning |
| 代码风格检查 0 违规 | 部分 | 依赖 `kotlin.code.style=official`；detekt 未接入（M1-3 待做） |
| 死代码 0 处 | 部分 | 已手工清除确认的死代码；detekt 未接入（M1-5 待做） |
| 静态代码检查 0 严重 | 部分 | 同上 |

### 9.3 已知豁免清单（§5.2 要求的显式清单，第一条）

| 位置 | 检查项 | 理由 | 复核时机 |
|---|---|---|---|
| `DayTimelineView.onTouchEvent` | `ClickableViewAccessibility` | 该检查只在 `onTouchEvent` 函数体内做直接调用扫描，无法穿透 `GestureDetector` 委托；`performClick()` 实际在 `onSingleTapConfirmed` 中调用，无障碍契约已满足。**精确豁免单条，非全局关闭** | M4 接入点击逻辑后随 Q8 一并在真机复核 |

### 9.4 后续优先事项

1. **M4 交互闭环**——这是当前最大缺口，且 **D3（取消零事件）** 与 Q1 是发布红线，不可压缩。
2. **M1 剩余门禁**——JaCoCo 覆盖率阈值是 T4 的唯一凭据，越晚接入越难补。
3. **AD-05 无障碍虚拟视图**——R7 登记为易被压缩项，且 M5 出口标准锁死 Q8。
4. **外部滚动模式与 E29 手势归属**——AD-04 的四条规则尚未验证，是 R2 的核心。
5. **M0 色值定稿**——解除 AD-12 的占位状态，复查深色对比度（Q7）。

