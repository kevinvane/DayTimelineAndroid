# DayTimeline 技术方案与实施计划

| 项 | 内容 |
|---|---|
| 文档版本 | v0.14（草案） |
| 状态 | 待评审 |
| 编写日期 | 2026-09-30 |
| 对应 PRD | `docs/DayTimeline-产品与需求文档.md` v1.9（v1.7 为 Q1 取消路径口径澄清，见 AD-25；v1.8 关闭 R9 并新增 R12；v1.9 按 OQ-7 方案 A 支持 Java 接入，见 AD-02；FR / FI 编号未变） |
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

### AD-02　时间用编译期安全的值类型（关闭 R9；v0.14 起改为普通类以支持 Java 调用方）

**决策（v0.14 修订后）**：

```kotlin
class MinuteOfDay private constructor(val minuteOfDay: Int) {
    val hour: Int; val minute: Int
    override fun equals(other: Any?): Boolean = other is MinuteOfDay && other.minuteOfDay == minuteOfDay
    override fun hashCode(): Int = minuteOfDay
    companion object {
        @JvmStatic fun of(hour: Int, minute: Int): MinuteOfDay   // 唯一正常构造入口
        @JvmStatic fun ofMinute(minuteOfDay: Int): MinuteOfDay
        @JvmStatic fun parse(text: String): MinuteOfDay?          // "09:30"
        @JvmField val START_OF_DAY: MinuteOfDay
        @JvmField val END_OF_DAY: MinuteOfDay                    // 24:00
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

**关键结论 —— 风险 R9 可以关闭（M1-8 已实测，2026-10-10）**：`value class` 是**编译期**特性，编译后展开为 `int`，**不依赖任何运行时 API 或 desugaring**。因此「最低支持版本 23」与「D18 编译期保障」**不构成冲突**，R9 提出的二选一（① 抬高 minSdk / ② 降级为运行期校验）**都不必选**。这个结论与「用不用 value class」无关——改为普通类后同样成立（普通类连擦除都不需要，兼容性只强不弱）。

实测证据（三条，均可在本机复现）：

1. `javap -p` 反编译 `library/build/tmp/kotlin-classes/debug/…/MinuteOfDay.class`：类内唯一字段为 `private final int minuteOfDay`，没有任何一处引用运行时 API（value class 时代方法签名全部收 `int` 吐 `int`，如 `getHour-impl(int)`；普通类时代为普通实例方法，见 v0.14 修订）。
2. `:library:assembleDebug` 在 `minSdk = 23` + Kotlin 2.0.21 下通过；`library/build.gradle.kts` **未启用** `isCoreLibraryDesugaring`，依赖里也没有 `desugar_jdk_libs`——不存在为支持值类型额外引入的兼容层。
3. 74 个仪器测试在真机（Pixel / Android 9）全绿，`MinuteOfDay` 被 `core/`、`api/`、`internal/`、`paint/` 与 demo 全量使用。**口径说明**：真机型号为 API 28，不是 API 23；但产物只有 `int` 运算与 `kotlin-stdlib`（Kotlin 插件已携带），不含任何按 API 级别分支的行为，「与 minSdk 23 并存成立」这个结论由证据 1、2 独立成立，真机证据负责的是「行为在真实运行时不退化」。

**代价与 v0.14 修订（OQ-7 决议：支持 Java 调用方）**：

- ~~`value class` 会被擦除为 `int`，Java 调用方看不到这个类型，且无法实现 `TimelineEvent` 契约。~~ **已解决**：`MinuteOfDay` 改为普通类。原因是 Kotlin 会对**返回值类型为值类型**的方法名做编译期混淆，`javap` 显示接口只有 `int getStart-ZruiD9E()` / `int getEnd-ZruiD9E()`，`-ZruiD9E` 不是合法 Java 标识符，`javac` 实测按 `int getStart()` 实现直接报「不是抽象类或接口中方法的覆盖」（`@Override` 即编译失败）——Java 调用方根本无法实现数据契约。改普通类后方法名干净，Java 侧 `implements TimelineEvent` + `MinuteOfDay.of(9, 30)` 直接可用。
- **新代价 D：手写 equals / hashCode**。value class 的相等性与哈希由编译器按底层 `Int` 免费生成；普通类必须自己写。已由 `TimeTest` 三条用例钉住（同时刻相等且哈希一致、不同时不相等且不认裸 `Int`/文本/null、可作 Set/Map 键）。**新增字段时必须同步更新这两个方法。**
- **新代价 E：热路径恢复分配**。构造点共约 43 处（`EventSanitizer` 16、`SnapCalculator` 6、`EditSession` 4 等），集中在数据清洗与拖拽吸附；`OverlapLayoutEngine` 与绘制层零构造，布局/绘制热路径无新增分配。U12（200 条全同时）压测门槛不变，仍由既有用例守着。
- **新代价 F：R8 保留规则必须同步收紧**。`consumer-rules.pro` 里 `MinuteOfDay` 原是 `-keepnames class`（只保类名）。普通类的公开成员（`getMinuteOfDay`/`getHour`/`of`/`ofMinute`/`parse`）是业务方可读可调的 API，只保类名时**跨 R8 边界即失效**——`:r8test` 的 `javaCallerCanImplementContractAfterMinification` 实测抓到 `NoSuchMethodError: getMinuteOfDay()I`（androidTest APK 是独立的一次 R8，按原名调用已在 app APK 里改过名的成员）。已改为 `-keep class …MinuteOfDay { *; }`，与 `EditDraft`/`EventDetail` 同规格。
- **新代价 G：Java 接入仍有一点摩擦**。Kotlin 接口的默认实现（`expiredOverride`/`content`/`color`）在字节码层仍是抽象方法 + `DefaultImpls`（未开 `-Xjvm-default=all`），Java 实现方需自行给出缺省值。`:r8test` 的 `JavaBusinessEvent.java` 里有注释说明，将来若嫌摩擦大可评估 `-Xjvm-default=all`（本次不做）。
- 反射/序列化场景下 `MinuteOfDay` 是普通类，反射可见性反而比 value class 更好；无需额外桥接。
- 私有构造函数是编译期保证的关键，**不可为了方便改成 public 或加 `operator invoke`**。

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

**占位取值**：17 个色项当前取 Material 3 baseline 占位值（AD-12），**不是终稿**——设计定稿（M0）后需逐项替换，且深浅两套都要换。见 §7 开放问题 OQ-1。

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

### AD-14　绘制上下文必须复用（反每帧分配）　【执行期决策】

**背景**：首版把 `GridContext` / `BlockContext` 写成不可变 `data class`，在 `onDraw` 中每帧构造。`lint` 的 `DrawAllocation` 报为错误（我们配置了 `warningsAsErrors`，符合 T6）。

**问题实质**：200 条日程时每帧要 new 200 个 `BlockContext`，与 §12.1「增量更新 100 条 ≤ 16ms」「滚动掉帧率 ≤ 3%」直接冲突。这是真实的性能缺陷，不是 lint 误报。

**决策**：改为**字段可变**的普通类，组件内各持一份并在每帧改写复用；并在 KDoc 中明确要求实现方**不得长期持有 context 引用**。

**代价**：`data class` 的 `equals`/`copy` 便利性丢失。这是为性能必须付的代价，属有意取舍。

**延伸**：`GridPainter.Paints` / `EventBlockPainter.Paints` 包装同样提升为字段，消除最后两处每帧分配。

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

### AD-22　表单输入只补载荷、不建 UI　【PRD v1.3 决策】

**背景**：PRD v1.2 的 §7.7 编辑态规格、§10.1 可配置项总览、§11.3 事件表**都不含任何文本输入项**——组件一直只支持手势驱动的就地时间调整。业务接入方要收标题、备注、地点时，唯一合规路径是 §10.2 第四层「接管新建流程，改为自己弹出底部面板」。

但现有 `EditController` 载荷撑不起表单：只有 `onDone(range, isCreating)` 与无参 `onCancel()`。业务方**读不到草稿**（`EditSession` 是 View 私有字段，无法预填表单）、**不知道在改哪条**、**无法回传新标题**。

**决策：组件零新增 View，只把第四层载荷补成对称的双向契约。**

| 层 | 契约 | 载荷 |
|---|---|---|
| 组件 → 业务方 | `onEnterEditing(draft): Boolean` | `EditDraft`（是否新建、被编辑的日程、当前起止、当前内容） |
| 业务方 → 组件 | `view.applyEdit(result)` | `EditResult`（起止、标题；非法值由组件合法化） |
| 业务方 → 组件 | `view.confirmEdit()` / `view.cancelEdit()` | 退出路径不变，`confirmEdit` 仍是**唯一数据变更出口** |

**为什么不做内置表单**（逐条对应既有硬约束）：

| 约束 | 冲突 |
|---|---|
| §12.1 网格区常驻元素 **0** 个 | 内置面板要引入 View 树，指标口径要重新界定 |
| D7 不允许业务方改源码 | 内置表单把业务字段硬编码进组件 |
| §9.1 不侵入业务模型 | 组件一旦接收备注/地点，就得定义其校验与生命周期 |
| AD-09 单一来源 | 内置表单引入第二套主题体系与深色适配负担 |

**三条必须守住的实现约束**：

1. **D3 不削弱**。`applyEdit()` 只改草稿、不发事件；`EditSession.CancelResult` 仍是**无字段对象**，「取消路径在类型上无法表达数据变更」这条保证原封不动。新增的 `EditDraft`/`EditResult` 是普通数据类，不参与该路径。
2. **FI-010 接管后失效**（PRD §8.2.1）。`onEnterEditing` 返回 `true` 后组件不再执行「点外部区域视为取消」——否则非模态表单会被点穿，产生「表单开着但草稿已取消」的不一致。View 侧加 `editTakenOver` 标志，建会话置位、三个出口清零。
3. **表单与拖拽走同一套时间合法化**。新增 `SnapCalculator.applyRange()`，**两端对称吸附**（D11）：现有 `snapResizeStart`/`snapResizeEnd` 是单边修正、只服务拖拽，不能复用于同时改两端的表单场景。该函数是纯 Kotlin（AD-02/T2），改 `core/` 必须同步补单测。

**业务色走第三条通道**：新增 `TimelineEvent.color: Int?`（**带默认实现**，既有实现者零改动）→ `SanitizedEvent` 透传 → `BlockContext.accentColor` → `DefaultPainters` 消费。**刻意不并入 §7.3 的 17 项主题色**——那 17 项的深浅适配由资源承担，业务方覆盖会破坏 AD-09 的机制。业务色的深浅适配责任在业务方（PRD §7.7.1）。

**破坏性变更**：`EditController.onDone`/`onCancel` 与 `TimelineListener.onEventCreated`/`onEventModified` 签名变更。库尚未发布（M6 未完成），现在改代价最小。

> **⚠️ 给默认值救不了既有实现者。** 一度以为「新增参数一律给默认值 `null`」就能保持源码兼容，**实测不成立**：写一个探针 `override fun onEventCreated(range: IntRange)` 去实现新接口，编译器报 `overrides nothing`。Kotlin 的接口默认参数**不会**被实现者继承——实现签名必须与声明完全一致。默认值只对**调用方**有价值（业务方自己调用这些回调的场景几乎没有）。结论：这是**硬性不兼容变更**，接入方必须改签名。

**阻塞前提**：§12.4 要求死代码 0 处。demo `MainActivity` 必须真实弹表单走通全链路，否则新增 API 全是死代码；且 `applyEdit`/接管语义都在 View 层，必须补仪器测试。

### AD-23　日程详情：组件只给只读快照与编程入口，UI 由业务方实现

**背景**：AD-22 落地后暴露一个新问题——业务方若要在「点击日程块后弹出详情」，只能自己从 `TimelineEvent` 上拼数据，且**无法发起任何操作**。

**决策：延续 AD-22 的纪律，组件零新增 View。** 只加三样东西：

| API | 作用 |
|---|---|
| `detailOf(id): EventDetail?` | 只读快照（id / range / content / color），字段全部取自组件**已持有**的兜底修正数据 |
| `enterEditMode(id): Boolean` | 以编程方式进入编辑态，**不**回调接管方 |
| `enterEditModeAndNotify(id): Boolean` | 同上，但**照常回调**接管方，语义与用户长按完全一致 |
| `clearSelection()` | 清除选中描边，不影响编辑态 |

**为什么必须是两个入口**

首版只做了 `enterEditMode`，内部直接调 `notifyEnterEditing()` —— 真机上表现为「点编辑弹出两个表单」。

根因是把「进入编辑态」与「弹出表单」耦合在一条路径上，而它们本该是两件事：

| 详情里的按钮 | 需要进入编辑态 | 该弹表单 |
|---|:--:|:--:|
| 完成 / 取消 / 删除 | 是（否则三个出口静默无效） | **否** |
| 编辑 | 是 | **是**，交 AD-22 表单接管 |

`enterEditMode` 用于前三者，`enterEditModeAndNotify` 用于「编辑」。**该缺陷的影响面比最初报告的大**：点「完成」「取消」「删除」同样会误弹表单，只是一直没人点到。

**为什么必须有 `enterEditMode`**

组件内部只有「编辑态」一个状态概念，而 `confirmEdit` / `cancelEdit` / `requestDelete` **都以 `editSession ?: return` 开头**。PRD FI-004 把「长按」定为进入编辑态的唯一手势，于是业务方在详情里点「删除」会**静默无效**——用户看到一个能点、点了没反应的按钮。这不是业务方能自己绕开的缺陷，组件必须提供不经手势的进入路径。

`enterEditMode` 在**已处于编辑态时返回 false 且不改变当前编辑对象**，否则会把用户正在编辑的草稿换成另一条而用户察觉不到。

**为什么不给业务方暴露可写草稿**

实现过程中一度想加 `editDraftOf()` 方便详情弹窗取草稿，**已否决**：那会让业务方拿到可改写编辑态的句柄，绕过 D3 的结构性保证（「取消路径在类型上就无法表达数据变更」）。业务方只能用只读的 `detailOf`。

**交互分工**（PRD §7.7.2）

单击 → 详情（只读，**清除选中描边**）；**长按 → 拖拽编辑态不变**（FI-004/006/007 均 P0）。

**四个必须遵守的约束**

1. **详情只读**，不得在其中直接改标题/时间/颜色——修改一律跳转 AD-22 的表单接管；
2. **数据取兜底修正后的值**，`detailOf` 已保证，业务方不得自行判断合法性；
3. **关闭即收尾**：`PopupWindow` 的 dismiss 回调与业务方操作存在竞态——若在 `dismiss()` 之后才调 `confirmEdit()`，前者可能已 `cancelEdit()` 导致后者遇到 `editSession == null` 静默失效。demo 用 `actionTaken` 标志区分「用户主动关掉」与「业务方已发起操作」；
4. **详情与表单不叠加**，跳转前先关闭详情。

**真机验证（本次实测，非推断）**

首版 `showAsDropDown(timeline)` 把弹窗推到屏幕外（anchor 是整个时间轴视图，底边远在屏下），真机表现是「只露出顶部一丝标题」。改用 `showAtLocation(decorView, Gravity.CENTER)` + 80% 屏宽上限后正常。

**另有一个只有真机能发现的崩溃**：`ContextCompat.getColor(context, android.R.attr.colorSurface)` 把**框架属性 ID** 当**应用资源 ID** 去查，真机抛 `Resources$NotFoundException`，而编译、lint、detekt 全部通过。正确写法是 `MaterialColors.getColor(view, attr)`。这与 AD-15（`format="float"` 致 `TYPE_FLOAT`）是同一类缺陷：**JVM 侧的检查对 Android 资源类型问题完全失明**。

**详细方案**：`docs/DayTimeline-日程块表单输入方案.md`（含 API 签名、不变式、测试用例清单与未决问题 QF-1~QF-4）。

### AD-24　FI-012 首次定位：定位到「视口三分之一处」而不是固定像素　【PRD v1.6 决策】

**背景**：PRD 自 v1.1 起就要求 FI-012「首次显示自动定位到当前时间 / 可配置关闭」，M3-8 早已列为完成。但代码里**只有 `dtAutoLocateOnFirstShow` 这个属性被声明，没有任何地方读它**——`Dimens.firstLocateLeadIn`（48dp）只服务于 `scrollToMinute`（FI-013），与「首次定位」无关。用户提问「是否支持自动滑动到当前系统时间」时才发现：**配置项存在，功能不存在**。这是比「漏实现」更糟的形态——它连报错都没有，接入方配了也以为生效了。

**决策**：实现 FI-012，落点与规则全部由 PRD 给出，实现只做三件事：

| PRD 出处 | 规则 | 实现 |
|---|---|---|
| §8.5 | 首次定位 = 当前时间向上偏移约**三分之一屏** | `Geometry.firstLocateOffset()`，`viewportHeight / 3` |
| §8.5 | **非今天**定位到 00:00（顶部） | `autoLocateOnFirstShow()` 里的显式守卫 |
| FI-012 | 可配置关闭 | `TimelineConfig.resolvedAutoLocateOnFirstShow()`，默认 `true` |
| §11.2 / D12 | 业务方显式恢复的位置、以及状态恢复的位置，**优先于**自动定位 | `initialScrollSettled` 标志 |

**为什么是三分之一屏而不是固定 48dp**

固定值在小屏上把当前时间推到屏幕顶外、在大屏上几乎没滚动；三分之一屏在任何视口高度下都稳定成立，与因素密度无关。§7.2 没有为「三分之一屏」定值，因为它是比例而不是尺寸——**不能放进 `dimens.xml`**（AD-15：无量纲比值写成 `format="float"` 会被 AAPT2 编译成 `TYPE_FLOAT`，`getDimension()` 直接抛异常）。因此 `FIRST_LOCATE_VIEWPORT_DIVISOR = 3` 是 `Geometry` 里的 `const`，并在 `FirstLocateTest` 里把它钉住。

**为什么把定位挂在 `onSizeChanged` 而不是 `submitEvents` 或构造期**

三分之一屏要用**视口高度**，而 `submitEvents` 可能在测量前就被调用（顺序由业务方决定，见 `MainActivity`：`setConfig` → `submitEvents` 全在 `onCreate`）。构造期根本没有高度。`onSizeChanged` 是第一个「尺寸已知」的点，且天然覆盖旋转/重建后的重新测量。

**显式意图优先：`initialScrollSettled` 的四个来源**

| 来源 | 场景 | 不这样做的后果 |
|---|---|---|
| `autoLocateOnFirstShow()` 已跑过 | 旋转后重新测量 | 每次尺寸变化都把用户拽回当前时间 |
| `scrollToMinute()` | 业务方跳转到指定时刻 | 自己跳的位置被覆盖 |
| `setScrollOffset()` | 业务方按 §11.2 恢复上次位置 | 保存的位置被覆盖 |
| `onRestoreInstanceState()` | 状态恢复（D12） | **恢复被覆盖，D12 直接失败** |

判断用独立标志而不是 `scrollOffset != 0`：业务方显式定位到 0（顶部）同样是有意为之。

**为什么瞬间定位而不是用 `scrollToMinute(smooth = true)`**

`scrollToMinute` 带 220ms 动画。首屏播一段从 00:00 飞到当前时间的动画，用户看到的是「页面自己动了 220ms」，而 FI-013 的平滑滚动是「用户知道自己在跳转」。首次定位必须无感，因此走 `Geometry.firstLocateOffset()` 直接赋值。为此顺带去掉了一个隐患：`scrollToMinute` 原本算 `minuteToOffset(minute) - leadIn`，**漏了顶部留白**，在所有非零 `topPadding` 下都偏上 8dp；现已统一到 `minuteToContentY()`，绘制、命中测试、跳转三处对同一分钟的换算从此同源（此前是同一公式抄五遍）。

**外部滚动模式（FI-002 / §8.6）不定位**

该模式下组件高度 = 全天内容高度，`maxScroll()` 恒为 0，`firstLocateOffset` 算出的目标被钳回 0——不产生位移。滚动归外层容器负责，组件无权也无需驱动外层位置。这一条由仪器测试 `externalScrollModeLeavesScrollingToOuterContainer` 钉住。

**连带影响：17 个存量仪器测试因该功能而失败**

`DayTimelineViewEditTest` 的用例把事件放在 01:00 并用 `visibleBlockSnapshot` 定位它——自动定位到当前时间后，01:00 已滚出视口。**这不是组件缺陷，是测试夹具的隐含前提（「初始偏移为 0」）失效。** 两个夹具的 `setUp` 里已显式 `setConfig(autoLocateOnFirstShow = false)` 并注明原因。这正是「可配置关闭」的正当用途：需要确定起点的场景关掉它。**首轮改动只做加法时漏掉了这一步，是本次实施中唯一的返工。**

**验证**（真机 Pixel / Android 9，非推断）

`firstShowAutoLocatesToOneThirdViewport` 断言不变量「当前时间线的内容坐标 − 偏移 == 视口高度 / 3」，而非照抄公式；另覆盖可关闭、显式位置优先、非今天停顶、外部模式不位移、只定位一次、状态恢复不被覆盖、无数据时同样定位。

**详细方案**：PRD §8.5 / FI-012；测试见 `DayTimelineViewBehaviorTest` 的 FI-012 专区与 `FirstLocateTest`。

### AD-25　E20 自动取消与用户取消拉齐，取消路径收敛为单一清零出口　【PRD v1.7 决策】

**背景**：评审 PRD §14.3 Q1 时发现，Q1 名为「编辑态『取消』绝不产生数据变更通知」，
但 D3 与 §8.2 的硬性要求是「**任何**『取消』路径」。组件内实际存在**四条**取消路径、
四份独立代码：

| 路径 | 落点 | 修复前 |
|---|---|---|
| FI-009 取消 | `cancelEdit()` | 合格：状态全清、发「编辑取消」 |
| FI-010 点外部 | `handleTap` → `cancelEdit()` | 合格：复用同一出口 |
| **E20 自动取消** | `submitEvents` 里 `editSession?.onDataChanged(...)` 返回 null | **只清了 `editSession`** |
| 接管后退出 | 业务方调 `cancelEdit()` | 合格 |

**问题**：E20 分支遗留 `selectedId` / `editDraft` / `editTakenOver` / `grabbedHandle` 四项：

1. 选中描边残留在屏幕上，直到下一次无关刷新才消失；
2. **「编辑取消」事件不发** → PRD §15「编辑完成率 = 完成 /(完成 + 取消)」的分母漏掉这部分，
   完成率虚高，指标测不出问题；
3. **最严重的一条**：`editTakenOver` 残留为 true 时，后续静默的 `enterEditMode`
   进入编辑态后，`handleTap` 看到残留标志会直接 `return`，**FI-010 被静默禁用**——
   点外部不再取消，PRD §8.2.1 应有的行为凭空消失，且没有任何报错。

**决策**：

1. 抽出私有 `clearEditState()`，把「清零编辑态状态」收敛成**单一出口**，
   确认 / 取消 / 删除（接管 / 未接管）/ E20 五处共用。
   顺带修掉 `confirmEdit` 独独漏清 `grabbedHandle` 的不一致（今天不成缺陷，
   因为 `ACTION_DOWN` 会无条件重算它——但那是靠下游兜住，不是这里清干净了）。
2. E20 自动取消与用户取消**完全等价**：同样走 `clearEditState()` + `editController?.onCancel(draft)`
   + `listener?.onEditCancelled()`，一个都不少。通知接管方是必须的——
   否则业务方的表单是组件外部的窗口，草稿已死而表单还开着，正是 §8.2.1 要防的状态。

**测试**：新增 5 条仪器测试（`DayTimelineViewEditTest`），其中 E20 的三条沿用本文件既有的
「记录全部回调」断言方式——`assertEquals(listOf("cancelled"), fired)`，
新增数据变更回调会立刻失败而不是悄悄漏检：

| 用例 | 锁住什么 |
|---|---|
| `e20AutoCancelEmitsNoDataChangeAndClearsAllEditState` | 零数据变更 + 五项状态全清 + 触发刷新 |
| `e20AutoCancelRestoresTapOutsideCancelForTheNextSession` | 接管标志残留导致 FI-010 失效的回归 |
| `e20AutoCancelHandsDraftToControllerSoItsFormCanClose` | 接管态下 E20 也回调 `onCancel(draft)` |
| `submittingDataThatKeepsTheEditedEventDoesNotCancel` | 对照组 E21 / E28：不得误取消 |
| `confirmEditAlsoClearsGrabbedHandleLikeEveryOtherExit` | 五个清零出口清同一批状态 |

**未决**：~~`onTouchEvent` 的 `ACTION_CANCEL` 仍落入点击 / 长按分派~~ —— **已修，见 AD-26**。

### AD-26　`ACTION_CANCEL` 只做清理，不得当作一次完整操作　【PRD v1.7 连带】

**背景**：`onTouchEvent` 里 `ACTION_UP` 与 `ACTION_CANCEL` 走同一个分支。
取消时（`:1114`）只做了三件清理——解父容器拦截、回收 `VelocityTracker`、不启动惯性——
随后**仍按 `gestureArbiter.onUp()` 的结果继续分派**：

| `intent` | 分派到 | 用户实际经历 |
|---|---|---|
| `Click` | `handleTap` | 凭空触发点击；若正处于编辑态且点在外部，还会触发 FI-010 取消 |
| `LongPress` | `enterEditByLongPress` | 凭空进入编辑态 |

`ACTION_CANCEL` 的语义是「这次手势被系统或父容器中断」——
下拉刷新抢手、侧滑返回、通知栏下滑打断。用户**没有完成任何操作**，
组件却替他执行了一次完整操作的副作用。

**测试侧当时是零覆盖**：全仓 `ACTION_CANCEL` 只出现在主源码两处，`src/test` 与
`src/androidTest` 一次都没命中过。也就是说这条路径此前既没被验证、也没被禁止。

**决策**：`ACTION_CANCEL` 分支**提前 return**，只做清理，绝不进入 `when (intent)`：

1. 把 `ACTION_CANCEL` 事件补喂给 `gestureDetector`——否则它内部 pending 的
   长按超时消息不会被撤掉（此前只喂 `ACTION_DOWN`）。
2. 仍做解拦截与回收 `VelocityTracker`，与原逻辑一致。
3. 返回值按 `intent` 是否为 `Scroll` 决定：判为滚动的仍不消费、交给父容器；
   其余返回 `true` 表示本次手势已被处理。
4. **编辑态保持不变**——手势被中断不等于用户做出了选择，
   组件不得替他取消（PRD §8.2「抬起 → 处于拖拽中 → 保留编辑态」的延续）。

**测试**：3 条仪器测试（`DayTimelineViewEditTest`），分别对应上表的三种可观察后果。

| 用例 | 锁住什么 |
|---|---|
| `actionCancelDoesNotFireTap` | 不得凭空触发点击 / 凭空进新建编辑态 |
| `actionCancelDoesNotStartEditingFromBlock` | 不得凭空进入编辑态、不得发长按回调 |
| `actionCancelKeepsExistingEditingStateUntouched` | 编辑态下被取消打断时，编辑态原样保留且零回调 |

**反向验证**：把修复回滚后重跑，**这 3 条同时转红**，失败信息正是上表描述的症状
（「凭空进入新建编辑态」/「凭空触发长按回调，实际回调：[click]」/「把编辑态改成别的状态」）。
这是判断「测试是否真的咬得住」的必要一步——写完就绿不等于测试有效。

**连带补齐的两处覆盖缺口**（同批处理，见 §9.5）：

- **外部滚动模式下的取消路径**：此前 `DayTimelineViewEditTest` 全程只设
  `autoLocateOnFirstShow` 与 `defaultNewDurationMinutes`，**从不设 `scrollMode`**，
  `EXTERNAL` 只在另两个文件里测过 `onMeasure` 高度与「不让外层滚动」，均不涉及编辑态。
  PRD §8.6 与 D6 要求「外部容器滚动时也不能崩、取消行为一致」，此前无任何证据。
  补 3 条：`cancelBehavesIdenticallyInExternalScrollMode` /
  `tappingOutsideStillCancelsInExternalScrollMode` /
  `e20AutoCancelAlsoWorksInExternalScrollMode`。

- **`:app` demo 的取消链路**：`app/src/` 此前**只有 `main`**，零自动化测试。
  而 demo 里两条最贴近真实用户的取消收尾——
  `BottomSheetDialog.onDismiss`（`MainActivity:227`）与
  `PopupWindow.onDismiss` + `actionTaken`（`EventDetailPopup:196`）——
  **都写在业务方侧、不在组件里**，组件自己的测试再绿也证明不了它们。
  新增 `MainActivityCancelPathTest` 6 条，断言方式刻意改为**比对业务方数据源快照**
  而不是数回调：D3 的实质是「用户的数据没被改」，直接比对数据更贴近后果，
  也不必往 demo 里塞测试专用钩子。
  **反向验证**：把 `onDismiss` 里的 `cancelEdit()` 删掉后，
  `cancellingTheFormChangesNothing` 与 `dismissingTheFormWithoutSubmittingChangesNothing`
  同时转红。

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
| FI-008~011 | 编辑态状态机 | **PRD Q1 专项**、场景 B/E | M4 |
| FI-012, FI-013 | 滚动定位 API | 单元测试 | M3 |
| FI-014 | 边缘自动滚动（跟随块而非手指） | 场景 C 跨屏长日程 | M4 |
| FI-015 | 命中测试扩大热区 | Q10 走查 | M4 |
| FI-016 | AD-05 | Q8 真机 | M5 |
| FD-001~007 | `api/` + AD-07 | E1–E8、场景 F/G | M3 |
| FC-001~006 | AD-08/AD-09 | 单元测试 + 走查 | M5 |
| PRD Q1–Q11 | 全局 | §14.3 底线验收 | M4 / M5 |
| T1–T7 | §5 基础设施 | §12.4 门禁 | M1 / M6 |

---

## 5. 测试与质量门禁落地

### 5.1 三层测试策略

| 层 | 范围 | 工具 | 依赖设备 |
|---|---|---|---|
| L1 纯 JVM 单测 | `core/` 全部 + `internal/` 的 diff 与状态机 | JUnit（已在 catalog） | 否 |
| L2 仪器测试 | View 的测量/绘制/手势/无障碍 | `androidx.test` + Espresso（已在 catalog） | 是 |
| L3 性能与真机走查 | §12.1 指标、PRD Q1–Q11 底线 | 手测 + 内存/掉帧观测 | 是 |

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

仓库已有 CI（`.github/workflows/ci.yml`），无 pre-commit 钩子。CI 已覆盖：`detekt` → `verifyNoDeadCode` → `assembleDebug` → `test`（L1）→ 覆盖率阈值（核心 + 可测逻辑）→ `lint`，另有仪器测试与 R8 混淆验证两个独立 job。**仍未接入**：pre-commit 钩子、依赖漏洞扫描、文档待办标记检查。

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
| M4-7 | **PRD Q1 专项：取消路径零数据变更事件**（D3，最高优先级回归），**覆盖全部四条取消路径**——FI-009 取消、FI-010 点外部、E20 自动取消、接管后关闭表单（PRD §14.3.1） | D3 / PRD Q1 | M4-6 |
| M4-8 | 边缘自动滚动（跟随**块**而非手指） | FI-014 / §8.4 | M4-4 |
| M4-9 | 编辑态 × 数据变更决策表（E20/E21/E28）；**E20 与用户取消等价**——状态全清 + 发「编辑取消」 | E20/E21/E28 / PRD Q1 | M3-3 |
| M4-10 | 高度不足 48dp 的块自动扩展热区 | FI-015 | M4-5 |

**出口**：场景 B / C / D / E 全通过；PRD Q1、Q3 达标；**取消路径单测覆盖全部四条入口**（含 E20 自动取消）。

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

**出口**：§7 / §12 全部达标；PRD Q7–Q10 达标。**R7：M5-6 不可压缩**。

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
- **M0 不再阻塞开发，只阻塞视觉终稿**：17 个色项已按 Material 3 baseline 占位（AD-12），M2 的默认配色照占位值实现完毕（见 §9.1）。真正被卡住的是**视觉终稿验收**——替换色值、复查深色对比度（PRD Q7 / R10），而不是「无法开工」。
- **M2 与 M5 的工作量风险最高**：M2 因全量自绘而比常规 View 组件重；M5 因无障碍（AD-05 自建虚拟视图）而重。PRD 给 M2 5 天、M5 5 天，**建议 M1 结束后按实际速率复核这两个估算**。
- **不可压缩项**：M5-6 无障碍（R7）、M4-7 取消语义（D3）。排期压缩时优先砍 M1 的工具选型讨论与文档，不要砍这两项。

---

## 7. 开放问题（需拍板）

> **编号说明**：本节编号为 **OQ-n**（原本为 `Q1`–`Q9`），与 PRD §14.3 的**产品底线 `Q1`–`Q11`** 不是同一套编号——此前同号不同义，正文中「Q1 专项」等引用两处含义。改名后本节不再占用 `Qn`，正文中出现的 `Q1`–`Q11` 一律指 PRD §14.3 的产品底线；涉及取消路径专项的引用额外加「PRD」二字以示强调。

| # | 问题 | 影响 | 建议 | 阻塞性 |
|---|---|---|---|:--:|
| OQ-1 | 17 项语义色项当前仅为 Material 3 baseline **占位值**（AD-12），设计终稿未出（M0 未完成） | 配色代码已可开发与验证，但视觉终稿无法验收；深浅两套需逐项替换 | 优先完成 M0，替换时逐项走深浅两套 | 中（原记「阻塞」已失效：配色实现未卡住） |
| OQ-2 | 「零第三方依赖」未区分**运行时**与**测试期**。Robolectric / MockK 可让 View 层可单测，但会突破字面表述 | 影响测试策略与覆盖率达成方式 | 建议改述为「**运行时**零非 AndroidX 第三方依赖；测试期允许」 | 高 |
| OQ-3 | K10 写「零第三方依赖」，§12.2 却允许 AndroidX 官方库。措辞冲突 | 影响依赖审查口径 | 统一为「零非 AndroidX 第三方运行时依赖」 | 中 |
| OQ-4 | ~~风险 **R9**（minSdk 与 D18 冲突）~~ | — | **已关闭**（2026-10-10）：AD-02 结论已由 M1-8 实测证实（`javap` 字节码擦除为 `int`、无 desugaring 兼容层、真机 74 仪器用例全绿），R9 已在 PRD v1.8 §18 整条重写为「已排除」 | 已关闭 |
| OQ-5 | ~~PRD §16.2 有重复段落：两次出现「排期为粗略预估…」~~ | — | **已处理**：冗余副本已删除，保留含「M0 未完成不得进入 M1」约束的完整版本（2026-10-10） | 已关闭 |
| OQ-6 | ~~PRD §18 的 R9 仍以旧前提描述~~ | — | **已处理**（2026-10-10）：按「结论采纳后整条重写而非删除」重写为已排除并附实测证据，见 PRD v1.8 §18 | 已关闭 |
| OQ-7 | ~~对外 API 是否需要 Java 调用方友好门面~~ | — | **已决议并实施（2026-10-10，方案 A）**：`MinuteOfDay` 由 `@JvmInline value class` 改为**普通类 + 私有构造函数 + `@JvmStatic` 工厂**，D18 编译期保证不受影响（裸 `Int` 仍赋不进去，TimeTest 有对照），Java 调用方现在可以实现 `TimelineEvent`（`:r8test` 的 `JavaBusinessEvent.java` + `javaCallerCanImplementContractAfterMinification` 守护，混淆后 7/7 真机全绿）。连带修掉 `consumer-rules.pro` 的 `MinuteOfDay` 只保类名不保成员（R8 跨 APK 边界 `NoSuchMethodError`）。**剩余小项**：Kotlin 接口默认实现未开 `-Xjvm-default=all`，Java 实现方需自写三个可选方法缺省值；是否开启该编译参数另行评估 | 已关闭 |
| OQ-8 | L2 仪器测试的 `androidx.test` 版本当前未验证可用 | 影响 M2 起的测试排期 | M1 验证 | 低 |
| OQ-9 | **表单输入的四个开放点**（空标题回落、表单打开时是否仍可拖拽、冲突态 E28 下能否提交、业务色深色适配责任） | 影响 AD-22 的实现细节 | 见《日程块表单输入方案》§9 的 QF-1~QF-4，当前均已给出建议默认值 | 中 |

---

## 8. 变更记录

| 版本 | 日期 | 变更内容 | 修订人 |
|---|---|---|---|
| v0.1 | 2026-09-30 | 初稿。基于 PRD v1.1 输出架构决策 AD-01~AD-11、M0–M6 任务拆解、测试与门禁落地方案、8 项开放问题。给出 R9 的技术结论（`value class` 使 minSdk 23 与 D18 兼容）。 | — |
| v0.2 | 2026-09-30 | 开工后补充执行期决策 AD-12（色项占位）、AD-13（core-ktx 降级）、AD-14（绘制上下文复用），并回写 §9 实施进度。 | — |
| v0.3 | 2026-10-08 | 真机崩溃排查后补 AD-15~AD-18：format=float 导致 TYPE_FLOAT 与 getDimension 不兼容、单位二次换算致字号放大三倍、XML 配置此前从未被读取且 setConfig 应为合并语义、补真机资源契约测试。 | — |
| v0.4 | 2026-10-08 | 真机暴露两个新缺陷后补 AD-19/AD-20：块内文字漏设 textSize 用了 Paint 默认的 12 原始像素；以及自身滚动模式**根本没实现拖拽滚动**（判定有、执行无）。同时补窄列文字省略号截断与对应真机回归测试。 | — |
| v0.5 | 2026-10-08 | 真机复测「滑动后回弹」后补 AD-21：`fling`/`startScroll` 参数填到了 X 轴而 `computeScroll` 读 `currY`，`currY` 恒为 0 导致惯性期间每帧写回 0。统一到 Y 轴并按 Intent 把关是否启动惯性。 | — |
| v0.6 | 2026-10-08 | 代码清理 + 进度回写订正。清除 `BlockContext.editing` 死字段（全仓零读取点，恒为 `false`，定制方据此判断只会永远拿到错误结果）与 `handleSelfScroll` 零调用私有函数（AD-20 修复残骸）；修复 `requestDelete` 在 `EditController.onDelete()` 接管分支只清 `editSession`、漏清 `selectedId`/`grabbedHandle` 且不刷新，导致选中描边与编辑层残留。§9.1/§9.2 此前仍把 M4/M5/M6 记为「未开始」，与代码严重脱节，本次按实际实现订正为 M2–M5 已完成、M1/M6 大部分或部分完成。 | — |
| v0.7 | 2026-10-09 | 对齐 PRD v1.3「日程块表单输入」：新增 **AD-22**——组件零新增 View，只把第四层 `EditController` 载荷补成对称的双向契约（`EditDraft` / `EditResult` / `applyEdit`）；`TimelineEvent` 新增带默认实现的 `color` 作为业务色第三通道；新增 `SnapCalculator.applyRange()` 做双边对称时间合法化（D11）；FI-010 在业务方接管后不再由组件执行（PRD §8.2.1）。详细方案落 `docs/DayTimeline-日程块表单输入方案.md`。文档头「对应 PRD」由 v1.1 订正为 v1.3。 | — |
| v0.8 | 2026-10-09 | 补齐 §12.4 剩余门禁并订正进度：① 接入 detekt + `verifyNoDeadCode` 死代码自动阻断，首次运行即抓出 4 处存量死代码；② 新增 `:r8test` 混淆消费端验证模块，首次运行即抓出 3 个发布阻断级缺陷（Kotlin 合成构造器、`$default` 桥接、`DefaultImpls` 合成类均被裁）；③ 修订 `verifyAllCoverage` 口径并**接入 CI**（此前从未进过 CI，实测 22% 长期为红），实测 88.79%，口径**已获产品认可**并回写 PRD v1.4 §12.4.1；④ 审查 `TimelineConfig.mergedWith` 时发现 `autoLocateOnFirstShow` 漏合并（XML 配了会被 `setConfig` 静默丢弃），已修复并补结构性断言 `TimelineConfigTest.mergedWith 覆盖全部可配置字段`——该断言不依赖阈值，新增字段忘合并即刻失败；⑤ §9.2/§9.4/§9.5 按实测重写，订正「detekt 未接入」「无 CI」等过期陈述；文档头「对应 PRD」升至 v1.4 | — |
| v0.9 | 2026-10-09 | 新增 **AD-23 日程详情**（对齐 PRD v1.5 §7.7.2）：组件零新增 View，只加 detailOf / enterEditMode / clearSelection 三个入口与 EventDetail 只读快照。记录 enterEditMode 的必要性——三个出口都以编辑态为前提，而 FI-004 只认长按手势，业务方在详情里点「删除」会静默无效；记录首版把「进入编辑态」与「弹出表单」耦合导致的「点编辑弹出两个表单」及拆分方案（影响面还包含完成/取消/删除）；记录被否决的 editDraftOf() 方案（会给业务方可写句柄，绕过 D3）；记录真机发现的两个问题：showAsDropDown 因 anchor 为整个时间轴而把弹窗推出屏幕，以及 ContextCompat.getColor 误用属性 ID 致真机崩溃（编译/lint/detekt 全部通过）。文档头「对应 PRD」升至 v1.5。**本次（v0.10）另行订正一处记录错位**：本行此前误写在 §9.5「已完成」表格末尾，使变更记录实际止于 v0.8 | — |
| v0.10 | 2026-10-09 | 新增 **AD-24（FI-012 首次定位）**。前情：`dtAutoLocateOnFirstShow` 属性自 v1.1 起就声明在 `attrs.xml` / `TimelineConfig` 里、M3-8 早已标为完成，**但全仓没有任何代码读取它**——「支持自动定位当前时间」是个只会回答「支持」的功能。本次补齐：定位规则取 PRD §8.5 的「视口三分之一屏」而非固定 48dp（比例无量纲，按 AD-15 走 `Geometry` 代码常量，不进 `dimens.xml`）；`onSizeChanged` 为触发点（视口高度首个已知时刻）；`initialScrollSettled` 令业务方显式位置与 D12 状态恢复均优先于自动定位；外部滚动模式下 `maxScroll()` 恒为 0，天然不位移。顺带统一 `minuteToContentY()`，修掉 `scrollToMinute` 漏加 `topPadding` 导致所有非零留白下偏上 8dp 的既有缺陷（同一公式此前在五处各抄一遍）。**连带返工**：17 个存量仪器测试因该功能生效而失败（夹具把事件放 01:00 并用 `visibleBlockSnapshot` 定位），已在两个夹具 `setUp` 里显式关闭自动定位并注明原因。门禁全部复跑通过，63 个仪器测试真机全绿 | — |
| v0.11 | 2026-10-10 | **评审 PRD Q1 的连带修订**（对齐 PRD v1.7）：① 新增 **AD-25**——评审 §14.3 Q1 时发现它字面只写「编辑态『取消』」，而 D3 与 §8.2 要求的是「**任何**取消路径」；实际有四条（FI-009 / FI-010 / **E20 自动取消** / 接管后退出），其中 E20 在 View 层只清了 `editSession`，遗留四项状态，导致选中描边残留、「编辑取消」事件不发（§15 完成率分母失真）、**接管标志残留会让下一轮 FI-010 被静默禁用**。修复方式是把清零收敛为私有 `clearEditState()` 单一出口（确认 / 取消 / 删除接管 / 删除确认 / E20 五处共用），顺带修掉 `confirmEdit` 漏清 `grabbedHandle`；E20 现与用户取消完全等价。新增 5 条仪器测试。② §7 开放问题编号由 `Q1`–`Q9` 改为 **`OQ-1`–`OQ-9`**——此前与 PRD §14.3 的产品底线 `Q1`–`Q11` 同号不同义，正文「Q1 专项」等引用两处含义。改后本节不再占用 `Qn`，正文中余下的 `Q1`–`Q11` 即为 PRD 底线；取消路径专项相关处（M4-7、M4-9、M4 出口、§4 对照表）另加「PRD」二字强调。③ **OQ-1 改写**：原文称色项「无取值、阻塞性=阻塞」，与本文 §9.1「17 项取值已就位（AD-12 占位）」自相矛盾，且配色实现早已开工——改为「已占位待设计定稿」，阻塞性降为「中」。④ OQ-5 标记已关闭（PRD §16.2 重复段落本次已删）。⑤ AD-09 与 §6.1 中「色项没有取值 / M0 是硬阻塞，无法开工」的过期表述同步订正为「阻塞的是视觉终稿验收，不是开发」。⑥ 覆盖率实测回写：核心 **91.73%**（原 91.64%）、可测逻辑 **88.49%**（原 88.79%）。⑦ **补回 AD-14**（绘制上下文必须复用）——该条内容此前在 AD-24 之后以无标题的孤儿段落存在，导致编号从 AD-13 直接跳到 AD-15，而 §8 变更记录与 AD-15/AD-18 都在引用它；已归位到 AD-13 与 AD-15 之间并补标题，内容与代码现状核对一致（`BlockContext` 为字段可变的普通类，KDoc 已写明不得长期持有）。⑧ **删除 AD-18 的两份残留重复副本**（位于 AD-25 之后、无标题、且比 §3 内的正文版本少一行表格与一句话），属早期编辑残留。**仪器测试已在真机复跑：`:library:connectedDebugAndroidTest` 68/68 全绿**（Pixel / Android 9），含本次新增的 5 条 E20 用例——本次修复的三个后果（选中描边残留、完成率分母漏计、FI-010 被静默禁用）现已全部有真机证据，不再是「编译通过即认为正常」。 | — |
| v0.12 | 2026-10-10 | **修复 `ACTION_CANCEL` 并补两处覆盖缺口**（AD-26）：① 新增 **AD-26**——评审时发现 `onTouchEvent` 的 `ACTION_UP` 与 `ACTION_CANCEL` 共用一个分支，取消时只做清理、随后**仍按 `intent` 分派**：判为 Click 会凭空触发点击（编辑态下还会顺带 FI-010 取消），判为 LongPress 会凭空进入编辑态。用户被父容器打断却收到一次完整操作的副作用。该路径此前**测试侧零命中**。修复为 `ACTION_CANCEL` 提前 return、只做清理，并补喂 `gestureDetector`（此前只喂 DOWN，其 pending 的长按消息不会被撤掉）；编辑态保持不变——手势被中断不等于用户做了选择。**反向验证：回滚修复后 3 条测试同时转红**，失败信息与缺陷症状逐条对应。② 补**外部滚动模式下的取消路径**（3 条）——此前 `DayTimelineViewEditTest` 从不设 `scrollMode`，§8.6 / D6「外部容器滚动时取消行为一致」零证据。③ **为 `:app` 建仪器测试**（`MainActivityCancelPathTest` 6 条）并接入 CI——`app/src/` 此前只有 `main`，而 demo 侧两条最贴近真实用户的取消收尾（`BottomSheetDialog.onDismiss`、`PopupWindow.onDismiss` + `actionTaken`）都写在业务方侧，组件测试再绿也证明不了它们。断言改为**比对业务方数据源快照**而非数回调。**反向验证：删掉 `onDismiss` 里的 `cancelEdit()` 后 2 条转红。** ④ §9.4 第 6 条结项，新增第 7 条记录 demo 其余路径仍无自动化（日期切换、配置面板、删除成功路径）。**顺带删除 ctivity_main.xml 的 edit_actions 三个常驻按钮**——编辑态入口收敛到表单与详情弹窗，理由、代价与验证见 §9.6。**本次该用例数从 6 增至 7**：新增 bandoningDeleteConfirmationCanStillEscapeByTappingOutside 钉住「删除二次确认放弃后 FI-010 是唯一出口」这条依赖 | — |
| v0.13 | 2026-10-10 | **完成 M1-8、关闭风险 R9**（对齐 PRD v1.8）：① AD-02 的「关键结论」从推理升级为**三条实测证据**——`javap -p` 反编译 `MinuteOfDay.class` 显示唯一字段 `private final int minuteOfDay`、方法签名全部收 `int` 吐 `int`（`getHour-impl(int)` 等），无任何运行时 API 引用；`:library:assembleDebug` 在 `minSdk 23` + Kotlin 2.0.21 下通过，`build.gradle.kts` 未启用 `isCoreLibraryDesugaring`、无 `desugar_jdk_libs`，即不存在为值类型引入的兼容层；74 个仪器测试真机全绿，擦除后的 `int` 在 API 23 运行时行为有真机证据。AD-02 原「待验证（M1）」三条中前两条证实、第三条被推翻（见 ②）。② **连带发现并实证：Java 调用方无法实现 `TimelineEvent` 契约**——`javap` 显示接口抽象方法为 `int getStart-ZruiD9E()` / `int getEnd-ZruiD9E()`，Kotlin 对值类型返回值的方法名做了编译期混淆，`-ZruiD9E` 非合法 Java 标识符；用 `javac` 实测按 `int getStart()` 实现，`@Override` 直接报「不是抽象类或接口中方法的覆盖」。AD-02 原判断「对象类型是 interface，问题不大」**对 Kotlin 调用方成立、对 Java 调用方不成立**。该限制已登记为 PRD §18 **R12**，OQ-7 按实测重写（原「建议提供静态工厂」的表述未反映「契约根本无法实现」这一事实），是否承诺 Java 接入待产品拍板。③ OQ-4 / OQ-6 关闭；§9.1 M1 行回写 M1-8 完成；§9.5 补两条（R9 关闭、Java 限制）。④ 顺带订正 §9.5 可测逻辑覆盖率为 88.49%（原 88.79%，v0.11 只改了 §9.2 门禁表，漏改此处） | — |
| v0.14 | 2026-10-10 | **实施 OQ-7 方案 A：`MinuteOfDay` 改普通类，Java 调用方可以接入**（对齐 PRD v1.9，产品已拍板）。背景：v0.13 发现 value class 的方法名混淆（`getStart-ZruiD9E()`）使 Java 无法实现 `TimelineEvent`，OQ-7 决议「改普通类」而非「另做 int 契约」——后者会让 Java 侧重新丢失 D18 的单位保护。改动与验证：① `MinuteOfDay` 由 `@JvmInline value class` 改为 `class … private constructor`，公有 API 一字未减（`of`/`ofMinute`/`parse`/`START_OF_DAY`/`END_OF_DAY`/`minuteOfDay`/`hour`/`minute`），新增手写 `equals`/`hashCode`（value class 时代由编译器按底层 Int 免费生成）与 `@JvmStatic`/`@JvmField`，全库约 115 处引用零改动编译通过。② **D18 未削弱**：私有构造函数仍在，裸 `Int` 依旧赋不进 `start`/`end`（Spike 与 TimeTest 对照均验证）；`TimeTest` 新增三条钉住 equals/hashCode（同时刻相等且哈希一致、不认裸 `Int`/文本/null、可作 Set/Map 键）——普通类转换最典型的连带风险就是相等性行为变化。③ **新增 Java 守护用例**：`:r8test` 增加 `JavaBusinessEvent.java`（Java 实现 `TimelineEvent`）与 `javaCallerCanImplementContractAfterMinification`（断言读回 title/起止时间而非只查没崩）。**反向验证：把 `MinuteOfDay` 换回 git 原版 value class，该 Java 文件立刻编译失败并直指 `getEnd-ZruiD9E()`**——回退会被构建当场拦住。④ **连带修掉一个 R8 规则缺口**：`consumer-rules.pro` 里 `MinuteOfDay` 原是 `-keepnames class`（只保类名）。普通类的公开成员是对外 API，只保类名时**跨 R8 边界即失效**——新用例首次运行即 `NoSuchMethodError: getMinuteOfDay()I`（androidTest APK 是独立的一次 R8，按原名调用已在 app APK 里改名的成员）。改为 `-keep class …MinuteOfDay { *; }` 与 `EditDraft`/`EventDetail` 同规格后转绿。**这条印证 §5 的判断：规则写错在单遍 R8 下完全隐形，只有真跑消费端才暴露。** ⑤ 代价记录进 AD-02：热路径恢复分配（构造点约 43 处，`OverlapLayoutEngine` 与绘制层零构造，U12 门槛不变）；Java 侧仍有 KDoc 已说明的「接口默认方法需自写缺省值」小摩擦（未开 `-Xjvm-default=all`，另行评估）。⑥ 全量门禁复跑通过：detekt 0 违规、死代码 0、核心覆盖率 **91.81%**、可测逻辑 **88.60%**、lint 0 警告、R8 符号核对 10/10；仪器测试真机全绿——`:library` **74/74**、`:app` **7/7**、`:r8test` 混淆变体 **7/7**（含本次新增的 Java 用例）。JVM 单测 141 → **144** | — |

---

## 9. 实施进度回写

### 9.1 当前状态

| 里程碑 | 状态 | 说明 |
|---|:--:|---|
| M0 设计定稿 | **部分** | 语义色项**结构**与 17 项取值已就位（Material 3 baseline 占位，AD-12）；交互标注、切图、字体资源仍缺，属设计职责 |
| M1 工程基线 | **已完成** | `:app`→`:library` 接线、res 骨架、`androidx.customview` 依赖、lint 严格配置、CI（`.github/workflows/ci.yml`）、JaCoCo 覆盖率门禁（核心 + 可测逻辑）、detekt 与死代码自动阻断（`verifyNoDeadCode`）、R8 混淆消费端验证模块 `:r8test` 均已建立。**M1-8（R9 结论实测）已完成**（2026-10-10），证据见 AD-02 |
| M2 静态呈现 | **已完成** | `core/` 全部算法 + U1–U16 逐条验收（20 个用例）、网格与日程块绘制均已完成 |
| M3 数据与滚动 | **已完成** | 数据契约、提交/单条更新/单条删除/切日期/指定当前时间、增量 diff + 滚动锚点、刷新收敛、30 秒定时器与生命周期摘除已完成；**两种滚动模式均已实现**——自身模式消费手势并执行惯性滚动，外部模式 `onMeasure` 按全天内容高度测量且 `Intent.Scroll` 分支 `return false` 让给外层（E29）。**FI-012 首次定位已于 v0.10 补齐**（此前 `autoLocateOnFirstShow` 无人消费，详见 AD-24） |
| M4 交互闭环 | **已完成** | `GestureArbiter` 手势状态机、`EditSession` 编辑态、`SnapCalculator` 上下对称吸附、48dp 热区手柄、`confirmEdit`/`cancelEdit`/`requestDelete` 与 `EditController` 第四层接管均已实现。**E20 自动取消已于 v0.11 与用户取消拉齐**（状态全清 + 发「编辑取消」，PRD §14.3.1），5 条新仪器测试真机全绿 |
| M5 打磨 | **已完成** | 字体放大标签降密度、`onSaveInstanceState` 状态保存、运行时资源重载（深色/多语言）、无障碍虚拟视图均已完成 |
| M6 发布 | **部分** | `consumer-rules.pro`（含 v0.14 补齐的 `MinuteOfDay` 成员保留）、README 已产出；**开启混淆的消费端验证已完成**（`:r8test:verifyKeptSymbols` + 混淆变体仪器测试 7/7，见 §9.2）——此行原文「仍未做」为 v0.8 之前的过期记录；**四份交付文档（DL-03~DL-06）仍未齐** |

### 9.2 门禁现状

| 门禁（§12.4） | 状态 | 证据 |
|---|:--:|---|
| 核心逻辑单元测试覆盖率 ≥ 90% | **达成** | `:library:verifyCoreCoverage` 实测 **91.81%**（9864/10744） |
| 平台规范检查 0 错误 | **达成** | `:library:lintDebug` 通过，0 error / 0 warning（`warningsAsErrors = true`） |
| 代码风格检查 0 违规 | **达成** | `:library:detekt` + `:app:detekt` 均 0 违规（53 → 0），配置见 `config/detekt/detekt.yml` |
| 死代码 0 处 | **达成** | `:library:verifyNoDeadCode` 专项门禁，实测 0 处；豁免清单见 `config/detekt/EXEMPTIONS.md` |
| 静态代码检查 0 严重 | **达成** | 同 detekt |
| R8 混淆后功能正常 | **达成** | `:r8test:verifyKeptSymbols`（10 个契约类全保留）+ `:r8test:connectedReleaseAndroidTest`（混淆变体仪器测试 **7/7**，Pixel / Android 9，2026-10-10，含 OQ-7 方案 A 的 Java 调用方用例 `javaCallerCanImplementContractAfterMinification`）。**该用例首次运行即抓出 `consumer-rules.pro` 里 `MinuteOfDay` 只保类名不保成员的缺口**（`NoSuchMethodError: getMinuteOfDay()I`），已修复 |
| 仪器测试（L2） | **达成** | `:library:connectedDebugAndroidTest` **74/74 全绿**（Pixel / Android 9，2026-10-10）。含 AD-25 的 5 条 E20 用例与 AD-26 的 3 条 `ACTION_CANCEL` 用例、3 条外部滚动模式取消用例 |
| 示例应用仪器测试 | **达成** | `:app:connectedDebugAndroidTest` **7/7 全绿**（`MainActivityCancelPathTest`）。**2026-10-10 新建，此前 `app/src/` 只有 `main`**——demo 侧两条取消收尾只靠注释自证；已接入 CI |
| 全库覆盖率 ≥ 75% | **达成（口径已修订）** | `:library:verifyAllCoverage` 实测 **88.60%**（3310/3736）。**口径与 PRD 原文不同**，见下方说明 |

#### 9.2.1 「全库覆盖率」的口径修订（**已获产品认可**，PRD v1.4 §12.4.1）

PRD §12.4 原文是「全库覆盖率 ≥ 75%」。字面执行的结果是 **22.11%**，且该门禁从未进过 CI、从未阻断过任何人（`git log -S` 查证）。原因是字面口径把结构上就拿不到 JVM 单测覆盖率的代码算进了分母：

| 包 | 指令数 | JVM 单测能否覆盖 |
|---|---:|---|
| `core` | 2572 | 能 |
| `api` | 1814 | 能 |
| `paint` | 1466 | 不能（全部走 `Canvas` 绘制，需真机） |
| `internal` | 949 | 不能（`Dimens.resolve` / `ConfigFromAttrs` 读 `Resources`） |
| 根包（`DayTimelineView`） | 3860 | 不能（测量/手势/编辑/无障碍，需真机） |

后三者合计 6275 条、占 59%，被排除。剩余范围内仍混有 getter/setter、`DefaultImpls` 空实现等**结构代码**，一并排除后分母从 17556 降到 3558。

修订后的口径：**「JVM 可测逻辑的覆盖率」**，被排除的三层由 40 个仪器测试与真机走查覆盖。

**但必须如实记录这条门禁的局限**：反向验证显示，往 `EditResult` 注入 5 段共 119 条真实的未测逻辑，覆盖率仅从 88.79% 降到 85.91%，**仍在 75% 之上**。即百分比门禁在基准远高于阈值时对少量漏测天然不敏感。

因此本门禁的定位是「抓大幅退化」，而非「抓漏测」。**防漏测靠的是结构性断言**，例如 `TimelineConfigTest.mergedWith 覆盖全部可配置字段` 直接比对字段集合——新增字段忘合并即刻失败，不依赖任何阈值。该测试在本次修订中实测抓出了 `autoLocateOnFirstShow` 漏合并的真实缺陷（XML 配了会被 `setConfig` 静默丢弃）。

**产品结论**：接受修订后的口径，已回写 PRD v1.4 §12.4.1 并同步 G7 / T4 措辞。**未决**：是否需要把仪器测试的覆盖率数据并入统计（需引入 `jacoco-android` 等第三方插件，与 K10 的边界有关），本次不做。

### 9.3 已知豁免清单（§5.2 要求的显式清单，第一条）

| 位置 | 检查项 | 理由 | 复核时机 |
|---|---|---|---|
| `DayTimelineView.onTouchEvent` | `ClickableViewAccessibility` | 该检查只在 `onTouchEvent` 函数体内做直接调用扫描，无法穿透 `GestureDetector` 委托；`performClick()` 实际在 `enterEditByLongPress` 中调用，无障碍契约已满足。**精确豁免单条，非全局关闭** | 已随 M4 在真机复核（`performClick` 由长按进入编辑态的路径触发） |

### 9.4 后续优先事项

1. **内置「完成/取消/删除」按钮**——PRD §7.7 要求可配置是否内置，至今组件未内置，按钮全在业务方侧；`attrs.xml` 已有警示注释，实现时一并加回。这是 §12.4 中目前唯一明确未实现的功能项。
2. **「声明了却无人消费」的专项审计**——AD-24 暴露的那类缺陷（`autoLocateOnFirstShow` 在 `attrs.xml` / `TimelineConfig` / `mergedWith` 里齐备，还有专项测试断言它被合并，唯独没有任何地方读它）在现有门禁下**完全隐形**：`verifyNoDeadCode` 只查私有成员，覆盖率门禁看的是「被测的逻辑」而非「被接到主流程上的逻辑」。建议的做法是把「每个可配置项至少有一个读取点」做成结构性断言（对照 `attrs.xml` 属性名 ↔ `ConfigFromAttrs` 读取处的字段名 ↔ View 层消费处），而不是再加百分比门禁。
3. **E29 与外部滚动模式的真机复核**——AD-04 的四条手势归属规则只经 `DayTimelineViewBehaviorTest.externalScrollModeDoesNotScrollItself` 单测覆盖，未在真机上验证过「外部容器滚动时组件不抢手势」。
4. **M0 色值定稿**——解除 AD-12 的占位状态，复查深色对比度（Q7）。
5. **pre-commit 钩子与依赖漏洞扫描**——§12.4「已知安全漏洞 0 个高危」与「文档无待办标记」目前无任何自动化手段。
6. ~~`ACTION_CANCEL` 落入点击 / 长按分派~~ —— **已于 v0.12 修复**（AD-26），并补 3 条仪器测试锁住。
7. **「组件绿但业务方链路断」这一类缺陷的常规防线**——AD-26 顺带暴露：`app/` 此前**零自动化测试**，
   demo 里 `BottomSheetDialog.onDismiss` / `PopupWindow.onDismiss` 两条取消收尾只靠注释自证安全。
   本次已为 `:app` 建 `connectedDebugAndroidTest` 并接入 CI，但**只有取消链路**被覆盖；
   表单提交、删除二次确认、日期切换、配置面板等 demo 路径仍无自动化。
   根因与第 2 条同源：门禁只看「被测的逻辑」，不看「被接到真实界面上的逻辑」。

### 9.5 已完成（原列为待办，现已具备证据）

| 项 | 证据 |
|---|---|
| AD-22 表单输入落地 | `applyEdit` / `EditDraft` / `EditResult` 已实现；demo 真实弹 BottomSheet 表单，真机手动验证通过 |
| AD-23 日程详情 | `detailOf` / `enterEditMode` / `enterEditModeAndNotify` / `clearSelection` 已实现；demo 真实弹 PopupWindow 详情，真机手动验证通过 |
| AD-24 首次定位（FI-012） | 见 AD-24。`firstShowAutoLocatesToOneThirdViewport` 等 9 个真机用例全绿；此前 17 个存量用例因该功能生效而失败并已修夹具（见 AD-24「连带影响」） |
| R8 混淆消费端验证 | `:r8test:verifyKeptSymbols` + `:r8test:connectedReleaseAndroidTest`（混淆变体 5/5）。首次运行即抓出 3 个发布阻断级缺陷 |
| detekt 与死代码自动阻断 | `:library:detekt` / `:app:detekt` 0 违规；`:library:verifyNoDeadCode` 专项门禁 0 处 |
| 可测逻辑覆盖率门禁 | `:library:verifyAllCoverage` 88.49%，已接入 CI（口径见 §9.2.1 / PRD §12.4.1） |
| **R9 关闭**（M1-8） | 三条实测：`javap` 显示 `MinuteOfDay` 不引用任何运行时 API（value class 时代擦除为 `int`，普通类时代为普通实例方法）；`minSdk 23` 构建通过且无 desugaring 兼容层；真机 74 仪器用例全绿。PRD v1.8 §18 的 R9 已整条重写为「已排除」 |
| **Java 调用方可实现 `TimelineEvent`**（OQ-7 方案 A，2026-10-10） | `MinuteOfDay` 改普通类后 `javap` 方法名干净（`getStart()` 返回 `MinuteOfDay`，无 `-ZruiD9E` 混淆后缀）；`:r8test` 新增 `JavaBusinessEvent.java` + `javaCallerCanImplementContractAfterMinification`，混淆变体 7/7 真机全绿。**反向验证：把 `MinuteOfDay` 换回 git 原版 value class，Java 文件立刻编译失败并直指 `getEnd-ZruiD9E()`**——守护用例确认咬得住 |
| **R8 保留规则补齐 `MinuteOfDay` 成员** | 由上面的新用例实测抓到（改普通类前该规则只保类名，跨 R8 边界 `NoSuchMethodError: getMinuteOfDay()I`）；已改 `-keep class …MinuteOfDay { *; }` 后复跑转绿 |
| AD-26 `ACTION_CANCEL` 不再触发点击 / 长按 | 见 AD-26。3 条仪器测试；**回滚修复后实测 3 条同时转红**，确认断言真的咬得住 |
| 外部滚动模式下的取消路径 | `cancelBehavesIdenticallyInExternalScrollMode` / `tappingOutsideStillCancelsInExternalScrollMode` / `e20AutoCancelAlsoWorksInExternalScrollMode`（此前该模式下取消路径零覆盖，D6 无证据） |
| `:app` demo 的仪器测试 | `MainActivityCancelPathTest` 7 条，接入 CI。**实测把 `onDismiss` 里的 `cancelEdit()` 删掉后 2 条转红**，确认不是空跑 |
| demo 编辑态入口收敛到弹窗 | 删除 `activity_main.xml` 的 `edit_actions` 三个常驻按钮，「完成 / 取消 / 删除」改由表单与详情弹窗提供。**代价与验证见 §9.6** |

### 9.6 demo 编辑态入口收敛（2026-10-10）

**`edit_actions` 已删除**

#### 改了什么

`activity_main.xml` 底部常驻的「完成 / 取消 / 删除」三个按钮（`edit_actions`）
连同 `MainActivity.kt:94-96` 的三行监听一并删除。三个出口改由业务方自己的
弹窗提供：`sheet_event_form.xml`（表单，确定 / 取消）与
`popup_event_detail.xml`（详情，完成 / 取消 / 删除 / 编辑）。
布局约束同步由 `Bottom_toTopOf="@id/edit_actions"` 改为 `Bottom_toBottomOf="parent"`。

#### 为什么它一度看着像死代码

`MainActivity` 的 `EditController.onEnterEditing` **恒返回 `true`**，接管必然生效，
FI-010 随即失效；而进入编辑态的四个入口里，三个会立刻弹出模态表单盖住底部按钮，
第四个（`enterEditMode` 静默）来自详情弹窗且紧跟 `confirm/cancel/requestDelete`。
按代码推理，「按钮可点且有用」的状态似乎不存在。

`MainActivity:93` 的注释还写着「未接管时用页面底部的按钮走传统路径」——
**这一分支在 demo 里永远走不到**。注释与代码不符，正是它差点被误当死代码删掉的原因。

#### 实测推翻了这个结论

唯一会「有编辑态却没有任何对话框」的状态是：**详情 →「删除」→ 二次确认对话框按返回键放弃**。
此时 `AlertDialog` 两个按钮的 listener 都不触发、`performDelete` 不执行，
编辑态原样保留（PRD 场景 E 的 E4「放弃 → 回到编辑态，数据不变」），
而详情已关、表单没弹——`edit_actions` 的「取消」是当时唯一显眼的出口。

已用 `abandoningDeleteConfirmationStillLeavesAnExit` 钉住该事实，
再据此删除按钮并把该用例改写为断言 FI-010 出口
（`abandoningDeleteConfirmationCanStillEscapeByTappingOutside`）。

#### 代价：demo 现在依赖 FI-010

删除后该状态下唯一出口是「点组件外部区域视为取消」（FI-010）。
实测成立——`enterEditMode` 不调 `notifyEnterEditing`，故 `editTakenOver` 为 false，
FI-010 生效，用户能退出且数据未变。

但这是一个**没有任何提示的手势**：屏幕上只有一个高亮选中块，
刚关掉模态对话框的用户多半不会再点。功能没坏，**可发现性变差**。
将来若改动 FI-010 的语义或让 `editTakenOver` 在此路径上残留，
demo 会静默地失去出口——这条依赖已由上述用例守住，改 FI-010 时必看它。

#### 顺带修掉的一个隐患

删除后 demo 里**不再存在永远可见、点了没反应的按钮**。
那正是 PRD §7.7.2 点名的反例（「用户会看到一个能点、点了没反应的按钮」），
demo 自己原本踩着。

#### 未变的部分

- 「业务方自备完成 / 取消 / 删除按钮」这一模式**没有丢失**：
  `popup_event_detail.xml` 的四个按钮本来就是它的完整演示。
- 删除成功路径的可达性不变（走 `performDelete`）。
- 组件侧一行未动——组件本来就不内置按钮（PRD §7.7 的「可配置是否内置」仍未实现，见 §9.4 第 1 条）。

#### 门禁

detekt / lintDebug / test / verifyNoDeadCode(0) / verifyCoreCoverage(91.73%) /
verifyAllCoverage(88.49%) / verifyKeptSymbols 全绿；`:app:connectedDebugAndroidTest` 7/7 全绿。