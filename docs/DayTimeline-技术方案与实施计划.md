# DayTimeline 技术方案与实施计划

| 项 | 内容 |
|---|---|
| 文档版本 | v0.33（草案） |
| 状态 | 待评审 |
| 编写日期 | 2026-09-30 |
| 对应 PRD | `docs/DayTimeline-产品与需求文档.md` **v1.26**（v1.7 为 Q1 取消路径口径澄清，见 AD-25；v1.8 关闭 R9 并新增 R12；v1.9 按 OQ-7 方案 A 支持 Java 接入，见 AD-02；v1.10 补「编辑完成率」统计口径 §15.1；v1.11 定编辑态存续边界 §11.4.1 + 幂等约定，见 AD-27；v1.12 订正 M0 门禁表述、补记越门事实、R10 重写，见 AD-12；v1.13 以项目现状对齐全文；v1.14 补齐两项未实现对外能力（`eventAt` / `refreshNow`）并把 R10 评级上调为高/高，见 AD-28；v1.15 记录 M6 首次发布结果（DL-07 已交付、首个 JitPack 产物陈旧不可用），**未改动任何需求判定**；v1.16 重打 tag 发布 `1.0.0-rc1` 并固化「发布后核对产物」流程；v1.17 清除已废弃版本的引用；v1.18 / v1.19 色值定稿落地并加自动化门禁；v1.20 品牌强调色由紫改蓝（第二版设计定稿）；v1.21 记 §12.1 性能指标的可自动化边界落地（AD-29）；**v1.22 产品决议取消「可配置是否内置完成/取消/删除按钮」这项需求（§7.7）**；v1.23 M0-2 / M0-3 交付；v1.24 / v1.25 记录漏洞扫描首次执行与编辑态选框形状 / 横向范围；**v1.26 长按超时即进入编辑态并加拾起反馈（AD-31）**；**FR / FI 编号自 v1.15 起未变**） |
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
3. 当时 74 个仪器测试在真机（Pixel / Android 9）全绿（现为 `:library` 82 + `:app` 7，见 §9.2），`MinuteOfDay` 被 `core/`、`api/`、`internal/`、`paint/` 与 demo 全量使用。**口径说明**：真机型号为 API 28，不是 API 23；但产物只有 `int` 运算与 `kotlin-stdlib`（Kotlin 插件已携带），不含任何按 API 级别分支的行为，「与 minSdk 23 并存成立」这个结论由证据 1、2 独立成立，真机证据负责的是「行为在真实运行时不退化」。

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

**定稿取值（2026-10-12）**：17 个色项的浅/深两套共 34 个取值已由设计侧定稿交付（`docs/DayTimeline-设计侧定稿交付.md`），AD-12 的占位状态**解除**。替换只动两份 `colors.xml`，组件代码零改动——与 v0.17「零返工」的判断一致。OQ-1 随之关闭。

### AD-10　RTL 从第一天内置（E16）

几何计算产出**逻辑左/右**，绘制入口按 `layoutDirection` 统一映射；RTL 下时间轴区域位于右侧。

**禁止**「先做 LTR，后期再镜像」——AD-01 的绘制代码里若散落绝对 x 坐标，后期改造成本极高。AD-03 输出的 `PlacedBlock` 应携带逻辑方向字段而非绝对像素。

### AD-11　状态保存（D12 / E9 / Q9）

`onSaveInstanceState` 存：**查看日期** + 自身滚动模式下的 `scrollOffset`。**不存业务数据**
（L8 组件不做持久化）。外部滚动模式下只存查看日期，滚动位置交外层容器（§8.6 明确要求）。

**订正（v0.15）**：本条此前写的是「选中 / 编辑态标识」——**那是没实现的行为**。
实际代码（`DayTimelineView.kt:1462-1465`）只存了 `scrollOffset` 与 `viewDate`。
一个从未被实现、也从无测试覆盖的描述留在架构决策里，比不写更糟：后来者会以为
编辑态跨实例存活是承诺，查不到实现也查不到测试。

**为什么不实现它**：草稿含业务方数据，序列化它就突破了 PRD §11.4「组件不做持久化」的
承诺；且 D12 / E9 / Q9 的验收范围本就是「滚动位置与查看日期」，不含草稿。
PRD §11.4.1（v1.11）已把编辑态的存续边界写成规格，见 AD-27。

### AD-12　语义色项取值：Material 3 baseline 作工程占位　【执行期决策】

**背景**：PRD §7.3 只定义「有哪些语义色项」，**具体色值由 M0 设计交付**，而 M0 尚未完成。§16.2 明确「M0 未完成不得进入 M1」。

**决策**：不等 M0，用 **Material 3 baseline 调色板**给出一套完整可用的浅色/深色取值作为工程占位，并明确标注非终稿。

**理由（为什么可以不等）**：
- AD-09 的整个架构目的就是「色值集中在 `colors.xml` / `values-night/colors.xml` 两处」，定稿时**只改这两份文件的取值即可，组件代码一行不动**。语义色项设计本身就是为降低这种返工成本。
- app 主题已是 `Theme.Material3.DayNight.NoActionBar`，取 M3 同源色值在视觉上与宿主应用一致，不突兀。
- 不取值则任何配色相关代码都开不了工，M1–M2 全部停摆。

**影响与遗留风险**：
- **门禁已被越过的事实（v0.17 补记）**：上文「§16.2 明确 M0 未完成不得进入 M1」在决策当时成立，本条即为越过该门禁的决策。PRD 侧此前无任何记录，**已于 PRD v1.12 补记**，并把门禁改述为「不阻塞开工、阻塞验收」。同时 PRD §16.2 的 **M6 出口标准新增「§7.3 的 17 项色值全部为设计定稿值」**——给本条留一个发布前的硬卡点，而不是只写在这里。
- **R10 的形态已变，风险本身未消除**：R10 原表述「用占位色开发导致返工」经查**未发生**（M1–M5 全部实现完毕，替换色值只动两份 `colors.xml`）。仍然成立的是「占位色值随组件发布到业务方」——PRD v1.12 已按 R9 / R12 的惯例整条重写，可能性/影响评级沿用原值未擅自上调。
- **Q7 / FC-002 在本条解除前判不了**：定稿后需复查深色下的对比度（Q7 / UF-004）与换肤验收（FC-002）。当前深色取值未做对比度实测。
- ~~**M0-2 / M0-3 不在本条覆盖范围**~~ **（2026-10-15 已交付）**：本条当时只兜住了 M0-1 的色值，交互标注与字体/图标资源无任何兜底；两项已于 2026-10-15 交付（`docs/DayTimeline-交互标注与字体资源交付清单-设计回复.md`），见 §9.4 第 4 条。
- 定稿动作：替换两份 `colors.xml` 中的 17 个取值 → 跑 §14.3 Q7 与 FC-002 → 更新本条状态。

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

**已取消的属性**：`dtShowEditActions` / `dtShowDeleteAction` 已从 `attrs.xml` 移除，
`TimelineConfig.showEditActions` / `showDeleteAction` 同步移除。
原因是内置「完成/取消/删除」按钮这项需求**已于 PRD v1.22 由产品决议取消**——
组件恒不内置这三个按钮（理由见 PRD §7.7），声明一个永远不会生效的属性，
比不声明更糟：接入方看到它会以为配了就能用。**不会再加回来**，
业务方要的三个出口经第四层 `EditController` 接管实现。

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

**破坏性变更**：`EditController.onDone`/`onCancel` 与 `TimelineListener.onEventCreated`/`onEventModified` 签名变更。变更已随 AD-22（提交 `594e7f5`）落地。**唯一在世的已发布版本 `1.0.0-rc1`（tag `95d081c`，2026-10-11）即含这些新签名**，接入方按现行文档实现即可；本次变更从未以「缺这些签名」的形态对外发布过。**但「库尚未发布、改动代价最小」的窗口已经关闭**——此后任何签名变更都构成破坏性变更。

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

### AD-27　编辑态的存续边界：定规格而非改行为　【PRD v1.11 决策】

**背景**：自检 Q1 时列出的第 2 / 3 / 4 项。查下来发现它们不是三个独立缺陷，
而是**同一个问题**：「编辑态这个对象应该活多久、由谁负责收尾」从来没被写下来。

**证据：三份文档互相矛盾**

| 出处 | 说法 |
|---|---|
| PRD §11.4 | 「关闭页面后**不保留任何状态**（旋转恢复除外）」 |
| 技术方案 AD-11 | 「`onSaveInstanceState` 存：查看日期、滚动偏移、**选中 / 编辑态标识**」 |
| 代码 `:1462-1465` | **只存 `scrollOffset` 与 `viewDate`** |

AD-11 描述的是一个从未实现的行为（已在上方订正）。至于「detach 算不算关闭页面」，
PRD 没有任何边界——`View` 被 `removeView`、滑出视口时页面还在、实例还在，
正好落进两句话之间的缝隙。

#### 决策 1（E32）：detach 保留编辑态，**不补 reattach 重验**

定下四种情形的边界（PRD §11.4.1）：

| 情形 | 编辑态与草稿 |
|---|---|
| 页面销毁 / 实例回收 | 不保留 |
| 旋转 / 折叠致实例重建 | 不保留草稿，只恢复查看日期与滚动位置 |
| **移出窗口后重新挂回（同一实例）** | **保留** |
| 移出窗口且不再挂回 | 随实例回收 |

选「保留」而非「清除」的理由是**代价不对称**：清除会让用户输入的内容静默丢失，
而 §11.4 那句「不保留任何状态」会让人以为这是预期行为。

**但「保留」需要一个前提**：草稿引用的数据可能已被业务方改掉。最初的方案是在
`onAttachedToWindow` 补一次 `revalidateEditingAgainst(events)`。

**实测证明这个补丁是多余的**：把它删掉，82 条仪器测试仍全绿。原因是
`events` 只可能由 `submitEvents` 改变，而那里已经做过 E20 校验——
**数据变更的时点是提交那一刻，与 detach 无关**。于是回退，改为把这条**既有契约**
用测试钉住（`detachThenSubmittingChangedDataAutoCancels`），并在 KDoc 里写明
「不要在这里补重验」的理由，防止下一个人再犯。

> 这条值得单列：**一个看起来合理、方向正确的补丁，实测证明它什么都没做。**
> 如果只跑「新测试是否全绿」，就会把这个无效改动合进去。

#### 决策 2：`clearEditState` 收编 `stopEdgeScroll`

`stopEdgeScroll()` 原先只有 `onTouchEvent:1112` 一个调用点，五处退出编辑态的出口
都不调。实测确认无害——`edgeScrollRunnable` 自带 `editSession == null` 守卫。

但那是**靠下游自守卫**：守卫一旦被去掉，退出编辑态后边缘滚动会继续跑且不报错。
AD-25 已把「退出编辑态的状态」收敛成单一出口，这次把**副作用**也收进来——
`clearEditState()` 增加 `stopEdgeScroll()`，KDoc 写明「现在并无实际危害，
收在这里是为了不依赖下游兜住」。

**反向验证**：回滚这一行后 `cancellingStopsEdgeAutoScroll` 与
`confirmingStopsEdgeAutoScroll` 两条同时转红。

#### 决策 3（§14.3.1）：幂等约定写进 Q1 判定方式

`cancelEdit()` 开头 `editSession ?: return`，同一会话上重复调用只有第一次
产生 `onEditCancelled`。组件保证幂等是正确设计，但 Q1 的判定方式原先只写
「回调集合精确等于白名单」，没说第 N 次调用该断言成什么。

业务方很容易写出多窗口收尾代码——demo 的 `MainActivity:227` 与
`EventDetailPopup:196` 就是两条。PRD §14.3.1 补上三档断言表：

| 断言对象 | 期望回调集合 |
|---|---|
| 首次取消 | `{编辑取消}` |
| 同一会话上再次取消 | `{}` |
| 取消后再调完成 / 删除 | `{}` |

#### 测试

| 用例 | 锁住什么 |
|---|---|
| `detachAndReattachKeepsEditingWhenDataUnchanged` | E32 前半：数据未变则草稿原样保留（同 E27） |
| `detachThenSubmittingChangedDataAutoCancels` | E32 后半：数据变更一律在 `submitEvents` 那一刻生效，与 detach 无关 |
| `cancellingStopsEdgeAutoScroll` / `confirmingStopsEdgeAutoScroll` | 退出编辑态必须停掉边缘滚动（**正向**断言，非「靠自守卫」） |
| `repeatedCancelOnlyFiresOnce` | 幂等：重复取消只发一次 |
| `completingAfterCancelIsSilentNoOp` | 取消后所有出口静默无操作 |

---

### AD-28　补齐「声明了却没有代码」的两个对外入口　【执行期决策】

**背景**：v0.18 以项目现状对齐文档时查出两项 PRD 能力在全仓**没有任何读取点**：

| 位置 | 能力 | 实际状态 |
|---|---|---|
| PRD §11.1（P0） | 获取指定位置的日程（供业务方二次确认） | 只有**内部** `HitTester.hitTest`，无对外入口 |
| PRD §11.2（P1） | 刷新当前时间线（手动触发一次） | 只有 30 秒定时器 `nowTicker`，无对外方法 |

这与 AD-24 的 `autoLocateOnFirstShow` 完全同类——「声明在 PRD、写在 `attrs.xml`、有专项测试断言它被合并，唯独没有任何地方读它」。区别只在：**连声明都没有**，只有 PRD §11 两行字。这类缺陷在现有门禁下完全隐形（`verifyNoDeadCode` 只看私有成员，覆盖率门禁只看「被测的逻辑」），v0.18 的 §9.4 第 2 条已把「每个可配置项至少有一个读取点」列为待建的结构性断言。

**决策**：两个都补实现，不删需求。P0 项没有「删掉」这个选项；P1 项与之共用同一个执行体，补一个薄入口的代价接近于零。

#### 实现要点

| 项 | 做法 | 为什么不是另一种 |
|---|---|---|
| `eventAt(x, y)` | 直接调组件内部那份 `hitTester.hitTest(...)`，再转成 `EventDetail` | **不另写几何比对**。业务方拿它判断「这里点到了哪条」，结论必须与用户手指点到的相同；两套规则必然漂移，而漂移是隐性的 |
| `eventAt` 的返回值 | 复用 `detailOf()`，返回 `EventDetail`（兜底修正后的值） | 与「取只读详情」已是同一契约，业务方不必再学一种返回类型 |
| `eventAt` 的副作用 | 无。不动 `selectedId`、不进编辑态、不发任何回调 | 与 `detailOf` 同性质。若顺手把选中态也设上，业务方在「只是探一下位置」时会被动改视图 |
| `refreshNow()` | 调 `tickNowOnce()`，与 `nowTicker` **同一个执行体** | 两条路径共用一份实现。若给手动刷新另写一遍，「少做一步」的差异只会出现在手动路径上 |
| `refreshNow` 与 `setNowMinute` 的关系 | 不覆盖已固定的值（`overrideNowMinute != null` 时跳过取系统时钟） | 固定时间是测试语义，一次刷新就抹掉它会让任何含刷新操作的测试静默失效 |
| `refreshNow` 是否节流 | **不节流** | 业务方 `onResume` 调它必须真实生效。吞掉一次调用完全隐性，比多刷新一次的代价高得多 |

#### 顺带修掉的门禁自身缺陷（比两个 API 更值得记）

`registerCoverageGate` 的非结构代码分支原本用 `doc.getElementsByTagName("counter")` 遍历**全文档**的 `<counter>`。JaCoCo 的 XML 里 class、method、sourcefile、package **四级各带一个** INSTRUCTION counter，于是同一份指令被累加四遍：核心门禁打印 `9864/10744`，而报告里 class 级真实值是 `2466/2686`。

百分比是两个数同比缩放，所以**看**不**出来**——91.81% 是对的，指令数是假的。危害不在判定，而在于**任何人照打印值去核对报告都会对不上**，进而怀疑门禁本身。已改为只累加 class 元素的直接 `<counter>` 子节点，打印值回归真实值，百分比不变（91.81%），门禁判定不受影响。

`ignoreStructuralMethods = true` 的那条分支本来就是 class → method 逐级下钻，没有这个问题——这也解释了为什么两条门禁里只有一条的打印值是错的。

#### 阈值两难：没有提高 `LargeClass`

新增两个入口后 `DayTimelineView` 越过 `detekt` 的 `LargeClass`（`threshold: 900`）。本仓 `detekt.yml` 里 `TooManyFunctions` 的注释明写「真正的风险由 LongMethod / LargeClass 把关」——**调 LargeClass 等于把刻意保留的防线撤回**，且 900→ 当前值是典型的「贴着现状定数字」。因此改为拆分：

| 提出去的东西 | 为什么能提 |
|---|---|
| `SavedState` 状态保存载体 | 只读写 `scrollOffset` / `viewDate` / `selectedId` 三个字段，与视图行为无关 |
| `EditSession.toDraft()` / `toResult()` | `EditSession` 上的纯映射，不碰任何视图状态 |

两处提出后类回到阈值内，`detekt` 恢复 0 违规，**阈值一个都没改**。剩下的类是 900 行上下、66 个方法的自绘 View——真正该警惕的是「下次再加两个入口又顶到线上」，届时的正确动作仍然是拆，而不是调数字。

#### 测试

| 用例 | 锁住什么 |
|---|---|
| `eventAtHitsBlockCenter` | 命中块中心能取回该日程（含 id / range / content） |
| `eventAtOnEmptyAreaReturnsNull` | 空白处返回 null |
| `eventAtOnAxisAreaReturnsNull` | 时间轴区域不算命中 |
| **`eventAtAgreesWithRealTouch`** | **逐点比对 `eventAt` 的结论与 `onTouchEvent` 派发的点击对象**——这是「不能另写一套几何比对」的唯一保障 |
| `eventAtIsPureRead` | 不动选中态、不进编辑态、不发任何回调 |
| `eventAtOutOfBoundsReturnsNull` / `eventAtReturnsNullForScrolledOutBlock` | 越界与已滚出视口均不崩、返回 null（§9.3 脏输入容错） |
| `refreshNowNotifiesListener` / `refreshNowKeepsOverriddenNowMinute` | 回调一次且带上正确时刻；固定值不被覆盖 |
| `refreshNowAndTickerUseSameBody` | 手动与定时器两条路径回调次数、取值完全一致 |
| `refreshNowFollowsSystemClockWhenNotOverridden` | 未固定时间时跟随系统时钟 |

**回滚验证**：把 `refreshNow()` 改回空实现 → 4 条同时转红；把 `eventAt` 改回恒返回 `null` → `eventAtHitsBlockCenter` 与 `eventAtAgreesWithRealTouch` 转红。`eventAtIsPureRead` / `eventAtOutOfBounds*` 在这两次回滚中**不**转红——它们守的是另外两件事（不纯副作用、越界容错），不是「功能是否存在」，属性不同，不与前两类混为一谈。

#### 一次「写完就绿」被回滚戳穿：`restoreHierarchyState` 抓不到 Parcel 缺陷

拆完类要给 `SavedState` 补回归测试。第一版走 `saveHierarchyState` / `restoreHierarchyState`，写完 93 条全绿。**按本仓第二条教训做了回滚验证，结果是全绿——测试根本没咬住。**

破坏点：同时把 `writeToParcel` 的 `viewDate` 写成常量、把 `Parcel` 构造里的 `readLong()` 改成 `= 0L`，期望至少一条转红。实际一条都没红。

原因是 **Android 在同一进程内传递 `SavedState` 是对象引用，不经过 Parcel 序列化**。断言通过靠的是「拿到同一个对象」，`Parcelable` 的读写逻辑压根没被执行。也就是说这条测试证明的是「状态对象还在」，而不是「状态对象能存能读」——而后者才是进程被杀后恢复时真正走的路径，也是用户会遇到的状态丢失场景。

改成**真 Parcel 往返**（`writeToParcel` → `marshall`/`unmarshall` → `CREATOR.createFromParcel`）后，同一处破坏立刻让 4 条转红（含后续字段错位波及 `selectedId` 的连带失败）。

**记下来的教训**：`restoreHierarchyState` 看起来像「走了完整恢复链路」，实际上是短路路径。凡是验证 `Parcelable`，必须显式做 marshal/unmarshall，否则测的是引用传递。另外，`SavedState` 因此从 `private` 改为 `internal`——不为了测试放宽可见性到 `public`，`internal` 在 AAR 里依旧不外泄。

> **未在真机复跑的部分**：本条的仪器测试与回滚验证均在真机完成。**最终数据（2026-10-10）**：Pixel_XL_API_34(AVD) / Android 14 上 `:library` **102/102**（含 `DayTimelineViewEntryPointTest` 11 条 + `DayTimelineViewSavedStateParcelTest` 5 条）、`:app` **7/7**、`:r8test` 混淆变体 **8/8** 全绿。期间在 API 26 模拟器上也完整跑过一遍同样的 102 条，结论一致。

---

### AD-31：长按「超时即进入编辑态」并加拾起反馈（PRD v1.26 新增）

**背景**：PRD §8.2 原把「进入编辑态」挂在**抬起**阶段——「已触发长按但全程未移动 → 进入编辑态」。手感上要求用户**长按后先松手**才进入编辑态，再重新按住才能拖动，与 Google 日历（及主流日历 App）「长按拾起后不松手即可拖动」不一致。本次按产品决议改为**超时那一刻进入**。

**决策**：

1. **超时即进入**：`GestureDetector.onLongPress`（系统超时约 400–500ms）命中已有日程块时，**立即** `EditSession.beginEdit` + `notifyEnterEditing`，不等 `ACTION_UP`。超时取 `ViewConfiguration.getLongPressTimeout()`，**不提供按组件配置的入口**——对齐 Google 日历，也避免再引入一个需要产品维护的配置项。
2. **状态机新增 `markEditing()`**：`GestureArbiter` 此前只在 `editing=true` 时把位移判为 `DragMove`。超时进入后必须同步把 `editing` 置真，否则随后的位移会被判成 `Scroll` 并把手势让给外层容器——用户按住不放一拖，编辑态还在、块却纹丝不动。抽成独立方法而非改 `onLongPressTimeout` 的返回语义，是为了让「命中空白处」的超时仍保持 `editing=false`（空白处不支持长按）。
3. **抬手不再重复进入**：`ACTION_UP` 的 `Intent.LongPress` 分支改为直接返回 `true`（此前才调 `enterEditByLongPress`）。编辑态已在超时时建立，抬手只是回执。
4. **`ACTION_CANCEL` 保留编辑态**：超时那一刻编辑态已真实建立，手势被系统 / 父容器打断**不等于用户取消**（与 AD-26 的既有结论一致）。D3 不变——取消仍零数据变更。
5. **拾起反馈**：超时进入时 `performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)` 发一次触觉；被编辑块**原位淡出为幽灵**（`Canvas.saveLayerAlpha` 包住该块绘制，编辑层离开原位后原位只剩半透明原块），表达「已拾起、正在移动」。**刻意不引入任何新语义色**——17 项 × 深浅两套取值由 `verifyColorsFinalized` 锁死，抬升只能用淡化既有绘制表达，不能新增硬编码阴影 / 高亮色。

**规模约束**：幽灵绘制抽为文件级私有 `paintBlock(...)`（呼应 AD-14 的绘制提取惯例），把 `DayTimelineView` 类体压回 detekt `LargeClass` 阈值（900 行）内——**没有提高阈值**。

**验证**（均已实测，且**关键用例回滚会转红**）：

| 用例 | 断言 | 回滚后 |
|---|---|---|
| `LongPressPickupTest.longPressEntersEditingBeforeRelease` | 按下 → 等超时（手指未抬起）→ `isEditing()` 已为 true → 抬手仍为 true | 改回「抬手才进入」→ **转红** |
| `LongPressPickupTest.draggingAfterPickupKeepsEditingWithoutRelease` | 超时后不松手直接 MOVE，编辑态保持且不抛异常 | 同上 → **转红** |
| `DayTimelineViewEditTest.editedBlockFadesToGhostAtItsOriginalPosition` | 被编辑块原位像素与同状态块不同（被淡化） | 去掉 `saveLayerAlpha` → **转红** |
| `GestureArbiterTest`（新增 3 条） | `markEditing` 后位移判 `DragMove` 且消费手势；未标记时长按后位移仍判 `Scroll`；标记后抬手保留 `DragMove` | 状态机回归 → **转红** |

`LongPressPickupTest` 必须挂在**宿主窗口**（`TimelineTestHostActivity`）里：长按由 `GestureDetector` 在主线程**延迟**触发，「裸 Context + measure/layout」的 View 拿不到真实主线程 Looper 的配合（`DragSnapWiringTest` 正因此被 `@Ignore`）。

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

仓库已有 CI（`.github/workflows/ci.yml`）并有 pre-commit 钩子（`.githooks/pre-commit`，需先 `git config core.hooksPath .githooks`）。CI 已覆盖：`detekt` → `verifyNoDeadCode` → `assembleDebug` → `test`（L1）→ 覆盖率阈值（核心 + 可测逻辑）→ `lint`，另有仪器测试、R8 混淆验证、依赖漏洞扫描（`dependency-scan`）三个独立 job。**仍未接入**：文档待办标记检查。

---

## 6. 任务拆解

沿用 PRD §16.2 的里程碑与人天。**每项任务的「覆盖」列标注对应 PRD 需求编号**，便于验收时反查。

### M0　设计定稿（3 人天，设计 + 产品负责）— **已完成（M0-1 2026-10-12；M0-2 / M0-3 2026-10-15）**

| # | 任务 | 交付物 | 覆盖 |
|---|---|---|---|
| M0-1 | 17 项语义色项取值表 | 浅色 + 深色两套色值表 | §7.3 / FC-002 |
| M0-2 | 交互标注 | 状态流转图、手柄与热区标注 | §7.7 / §8.1 |
| M0-3 | 切图与字体资源 | 字体规格、图标 | UF-001 |

**出口**：§7.3 全部 17 项有色值定稿（M0-1），交互标注（M0-2）与切图与字体资源（M0-3）交付。

**状态（v0.17 订正）**：本节原写「未完成不得进入 M1」，标题原标「**当前唯一硬阻塞**」——两处都与本文 §6.1 / §7 OQ-1 / §9.1 自相矛盾（那三处写的是「M0 不再阻塞开发，只阻塞视觉终稿」）。v0.11 变更记录 ⑤ 声称订正了「M0 是硬阻塞」的过期表述，**实际只改了 §6.1 的一处，漏掉本节自身**。

且该门禁**并非只是措辞过期，而是真的被越过了**：2026-09-30 起即以 AD-12 占位取值开工（§6.1「关键路径：M0 → M1 → …，严格串行」这句当时也已不成立），PRD 侧直到 v1.12 才补记。**越门的后果经查为零返工**——M1–M5 全部实现完毕，替换色值时只动两份 `colors.xml`。

**当前实际进度**（2026-10-15）：**M0-1 已完成**——设计侧定稿交付到位，34 个取值全部替换，AD-12 占位状态解除。**M0-2、M0-3 已于 2026-10-15 交付**（`docs/DayTimeline-交互标注与字体资源交付清单-设计回复.md`）：M0-2 交互标注经核对与现有实现一致、**无代码改动**；M0-3 字体规格（`sans-serif` / `sans-serif-medium`，副标题行高 16sp）已落地 `Theme.kt`，切图判定「无位图需求」。**M0 三项齐备，出口标准已满足**。

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

- **关键路径**：M1 → M2 → M3 → M4 → M5 → M6，严格串行（PRD 已定为串行）。**M0 原排在关键路径最前并被设为硬门禁，2026-09-30 起即由 AD-12 事实上越过**（v0.17 订正：原文此处仍写「M0 → M1 → …」，与下一条自相矛盾）。
- ~~**M0 不再阻塞开发，只阻塞视觉终稿**~~ **历史记录，已走完**：17 个色项曾按 Material 3 baseline 占位（AD-12）开发，M2 的默认配色照占位值实现完毕（见 §9.1）。2026-10-12 设计定稿交付后，替换色值与复查深色对比度（PRD Q7 / R10）均已完成，此处阻塞解除。
- **M2 与 M5 的工作量风险最高**：M2 因全量自绘而比常规 View 组件重；M5 因无障碍（AD-05 自建虚拟视图）而重。PRD 给 M2 5 天、M5 5 天，**建议 M1 结束后按实际速率复核这两个估算**。
- **不可压缩项**：M5-6 无障碍（R7）、M4-7 取消语义（D3）。排期压缩时优先砍 M1 的工具选型讨论与文档，不要砍这两项。

---

## 7. 开放问题（需拍板）

> **编号说明**：本节编号为 **OQ-n**（原本为 `Q1`–`Q9`），与 PRD §14.3 的**产品底线 `Q1`–`Q11`** 不是同一套编号——此前同号不同义，正文中「Q1 专项」等引用两处含义。改名后本节不再占用 `Qn`，正文中出现的 `Q1`–`Q11` 一律指 PRD §14.3 的产品底线；涉及取消路径专项的引用额外加「PRD」二字以示强调。

| # | 问题 | 影响 | 建议 | 阻塞性 |
|---|---|---|---|:--:|
| OQ-1 | **已关闭**（2026-10-12）：17 项语义色项的浅/深两套共 34 个取值已由设计侧定稿交付并全部替换（`docs/DayTimeline-设计侧定稿交付.md`），AD-12 占位状态解除 | — | — | 已关闭；Q7 已按定稿色值重走查（1 项保留偏差见 PRD §19.4 v1.18） |
| ~~OQ-2~~ | ~~「零第三方依赖」未区分**运行时**与**测试期**~~ | — | **已关闭**（2026-10-13）：以「引入 OWASP Dependency-Check 构建期工具」的方式事实上落定——**运行时零非 AndroidX 第三方依赖；构建与测试期允许工具类依赖**。该插件只作用于构建期，不进入任何模块的运行时 classpath，因此 §12.2「第三方依赖：无」针对的发布物不受影响。**一并解决的既有阻塞**：§12.4「已知安全漏洞 0 个高危」此前无任何手段，现已建立 `verifyNoHighVulns` 门禁 | 已关闭 |
| OQ-3 | K10 写「零第三方依赖」，§12.2 却允许 AndroidX 官方库。措辞冲突 | 影响依赖审查口径 | 统一为「零非 AndroidX 第三方运行时依赖」 | 中 |
| OQ-4 | ~~风险 **R9**（minSdk 与 D18 冲突）~~ | — | **已关闭**（2026-10-10）：AD-02 结论已由 M1-8 实测证实（`javap` 字节码擦除为 `int`、无 desugaring 兼容层、真机 74 仪器用例全绿），R9 已在 PRD v1.8 §18 整条重写为「已排除」 | 已关闭 |
| OQ-5 | ~~PRD §16.2 有重复段落：两次出现「排期为粗略预估…」~~ | — | **已处理**：冗余副本已删除，保留含「M0 未完成不得进入 M1」约束的完整版本（2026-10-10） | 已关闭 |
| OQ-6 | ~~PRD §18 的 R9 仍以旧前提描述~~ | — | **已处理**（2026-10-10）：按「结论采纳后整条重写而非删除」重写为已排除并附实测证据，见 PRD v1.8 §18 | 已关闭 |
| OQ-7 | ~~对外 API 是否需要 Java 调用方友好门面~~ | — | **已决议并实施（2026-10-10，方案 A）**：`MinuteOfDay` 由 `@JvmInline value class` 改为**普通类 + 私有构造函数 + `@JvmStatic` 工厂**，D18 编译期保证不受影响（裸 `Int` 仍赋不进去，TimeTest 有对照），Java 调用方现在可以实现 `TimelineEvent`（`:r8test` 的 `JavaBusinessEvent.java` + `javaCallerCanImplementContractAfterMinification` 守护，混淆后 7/7 真机全绿）。连带修掉 `consumer-rules.pro` 的 `MinuteOfDay` 只保类名不保成员（R8 跨 APK 边界 `NoSuchMethodError`）。**剩余小项**：Kotlin 接口默认实现未开 `-Xjvm-default=all`，Java 实现方需自写三个可选方法缺省值；是否开启该编译参数另行评估 | 已关闭 |
| ~~OQ-8~~ | ~~L2 仪器测试的 `androidx.test` 版本当前未验证可用~~ | — | **已关闭**（2026-10-13）：实测可用。`androidx.test:core` 1.7.0（catalog 声明 1.6.1，经 `ext:junit` 1.3.0 / `espresso-core` 3.7.0 传递解析）、`ext:junit` 1.3.0、`espresso-core` 3.7.0，`AndroidJUnitRunner` 跑通 `:library` 108 + `:app` 7 条；**`ActivityScenario` 亦可用**——2026-10-13 新增 `androidTest/AndroidManifest.xml` 与宿主 Activity 以观测重绘收敛，依赖经 catalog 显式声明（`androidx.test:core`）而非依赖传递。另注一条相关的既有缺口：仪器测试对弹窗路径是**回避**而非覆盖的——`DayTimelineViewEditTest:203` 与
`MainActivityCancelPathTest:231` 都显式覆写 `confirmDelete` 以「避开真机上 `AlertDialog` 的时序」。
即弹窗本身**从未在仪器测试里被断言过**。这不属 OQ-8 范围（工具可用性已验证），
但与「界面自动化冒烟」的部分落地状态同源，登记在此备查。 | 已关闭 |
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
| v0.15 | 2026-10-10 | **Q1 残留问题的自检与修补**（评审 Q1 时列出 5 项，自检后修 1 与 5）：① **R8 侧 D3 只验了三分之一的边界**——`R8ConsumerTest` 的 listener 只覆写 `onEventCreated`，断言是 `assertNull(createdRange)`，`onEventModified` / `onEventDeleted` **根本没被记录**，它们若在混淆产物上被误触发不会红。新增 `ConsumerApiSmoke.RecordingListener`，覆盖 `TimelineListener` 全部 **8 个**回调并按名字记录（同时验证 `consumer-rules.pro` 里 `-keep public interface TimelineListener { *; }`），取消断言改为「集合精确等于白名单」。**反向验证**：往 `cancelEdit` 注入一个多余的 `onEventCreated` 后该用例转红，报错逐条列出实际回调 `[created, cancelled]`；**旧版断言在同一注入下会通过**。另加对照组 `confirmStillEmitsDataChangeAfterMinification`，证明混淆产物上回调链确实通、取消断言不是假通过。② **PRD 补 §15.1「完成次数 / 取消次数」口径**（PRD v1.10）——§14.3.1 新增四条取消路径后分项从未定义。写明 E20 必须计入取消（否则组件静默丢弃该事件时分母变小、完成率虚高，AD-25 修的正是这个）、**确认删除两者都不计**（走 `onEventDeleted`，是第三条独立结果）、关闭详情未点任何按钮计入取消、业务方不得自行排除 FI-010（该行为 P1 且可被接管语义关闭，跨业务方不可比）。③ 新增警示：**接管态下完成事件不同**——`confirmEdit` 无条件调用 `EditController.onDone`，返回 true 时组件直接返回、不再发 listener 事件，业务方只监听 `TimelineListener` 会把完成次数统计成 0。补 2 条仪器测试钉住该互斥关系。**未修**：② `onDetachedFromWindow` 不重置编辑态（编辑态中 detach 后草稿残留并会复活）、③ `cancelEdit` 不调 `stopEdgeScroll`（靠 `edgeScrollRunnable` 的 `editSession == null` 自守卫兜住，实测无害）、④ PRD §14.3.1 缺「同一状态多次取消」口径——三项均属「编辑态存活边界」的规格空洞，需先定规格再动手 | — |
| v0.16 | 2026-10-10 | **定规格而非改行为：编辑态的存续边界（AD-27 / PRD v1.11）**。自检 Q1 时列出的第 2 / 3 / 4 项，查下来是同一个问题——「编辑态该活多久、由谁负责收尾」从来没被写下来。证据：三份文档互相矛盾（PRD §11.4「关闭页面后不保留任何状态」／AD-11「onSaveInstanceState 存选中 / 编辑态标识」／代码只存了 `scrollOffset` 与 `viewDate`）。**① 订正 AD-11**：删掉那句从未实现、也从无测试覆盖的「选中 / 编辑态标识」——留在架构决策里的不实描述比不写更糟；并说明为什么不实现（草稿含业务方数据，序列化它就突破了 §11.4 的承诺）。**② PRD 新增 §11.4.1 + E32**：逐情形定边界，detach-reattach 保留编辑态；选「保留」而非「清除」的理由是代价不对称——清除会让用户输入静默丢失，而 §11.4 那句话会让人以为那是预期行为。**③ §14.3.1 补幂等约定**，Q1 白名单断言细化为三档。**④ `clearEditState()` 收编 `stopEdgeScroll()`**——原先只靠 `edgeScrollRunnable` 的 `editSession == null` 守卫兜住，实测无害，但那是「靠下游自守卫」；收进来后回滚该行会让 2 条测试转红。**一次无效改动被实测拦下**：最初在 `onAttachedToWindow` 补了 `revalidateEditingAgainst(events)`，方向看起来对，但删掉后 82 条仍全绿——`events` 只可能由 `submitEvents` 改变，那里已经做过 E20 校验。已回退，并把「不要在这里补重验」的理由写进 KDoc 与测试注释，防止下一个人再犯。**本条不改变任何现有行为**：detach 保留编辑态本就是既有行为，本次是把契约写下来并补测试钉住 | — |
| v0.17 | 2026-10-10 | **核查并订正「M0 设计定稿未完成」在三份文档里的记录缺口**（对齐 PRD v1.12，**纯文档修订，一行代码未动**，故未跑门禁——无代码变更时门禁结果无变化，不以「跑过了」充数）。**发现的问题不是措辞过期，是真有一处门禁被越过且无人记录**：§16.2 写着「M0 未完成不得进入 M1」，而 §9.1 记 M1–M5 全部已完成；越门决策就是 AD-12，却只存在于本文，PRD 侧零记录。① **§6 M0 章节订正**——标题由「当前唯一硬阻塞」改为「不阻塞开工，只阻塞视觉验收」，出口行删去「未完成不得进入 M1」并补实际进度：**v0.11 变更记录 ⑤ 声称订正了「M0 是硬阻塞」的过期表述，实际只改了 §6.1 那一处，本节自身漏掉了**；顺带记录 §6.1「关键路径 M0 → M1 严格串行」这句在当时也已不成立。② **§9.1 M5 由「已完成」回退为「实现完成，验收待 M0」**——PRD 的 M5 出口标准含「Q7 达标」，Q7 判定方式是设计走查（UF-004），色值是占位则 Q7 判不了；深色模式「机制」做完了不等于「视觉达标」，此前记「已完成」缺乏依据。FC-002 换肤验收同理。③ **§9.4 待办第 4 条由「M0 色值定稿」扩为三项**——原文只列了 M0-1，把 M0-2（交互标注）、M0-3（字体与图标资源）漏出待办：**这两项既无交付物也无兜底**，组件当前用系统默认 `Typeface`（`Theme.kt` 只设 `textSize`），M0 三分之二的范围此前实际处于无人跟踪状态。④ **AD-12 补记**：门禁越过的事实、R10 的形态变化（原「导致返工」经查未发生，风险转为「占位值随组件发布」）、M0-2/M0-3 不在本条覆盖范围。⑤ 文档头「对应 PRD」由 v1.10 升至 v1.12（此前漏记 v1.11）。**PRD v1.12 侧的配套修订**：§16.2 补记越门并改述门禁、M6 出口标准新增「17 项色值全部为设计定稿值」给发布前留硬卡点、§7.3 补「当前取值状态」、§18 R10 整条重写（沿用 R9/R12 惯例）——**风险评级沿用原值中/中未擅自上调，实际可能性已升高，是否上调待产品复核**。**本次不降低任何验收标准**：Q7 / FC-002 的验收时点只是从「M0 定稿前」后移到「M0 定稿后」，判定强度未变；FR/FI/U/E/Q 编号与判定零改动 | — |
| v0.18 | 2026-10-10 | **以项目现状为标准，对齐 PRD 与本文所有过期陈述**（纯文档修订，**一行代码未动**，故未跑门禁——无代码变更时门禁结果无变化，不以「跑过了」充数）。事实基线取自三处一手材料而非记忆：**JaCoCo 报告** library/build/reports/jacoco/.../jacocoTestDebugReport.xml（生成于 2026-10-10 15:27，晚于全部源文件）、**逐文件数 @Test**（:library 82、:app 7、:r8test 8，JVM 144）、**CI 三个 job 与门禁任务清单**。据此订正：① **覆盖率指令数是错的**——§9.2 核心覆盖率原文「91.81%（9864/10744）」，实读报告为 **2466/2686**（百分比一致、指令数凭空 4 倍）；§9.5 的「88.49%」、§9.6 门禁块的「91.73%/88.49%」全部回写为 **91.81%/88.60%**（当前值）。② **§9.2 补 4 行门禁落地情况**——原表只列 7 项，PRD §12.4 实际有 10 项；「已知安全漏洞 0 个高危」「文档完整性」**完全未落地**（无工具、无 CI、无 Gradle 步骤），「界面自动化冒烟」「性能基准」**部分落地**。此前无论述，只读本文的人会以为全部已跑通。③ **§9.2.1 的包指令数表标注为 v0.8 期快照**，并补 2026-10-10 现值（core 2466/2686、api 1035/1929、paint 0/1498、internal 0/985、根包 0/4036，合计 11134）。④ **§9.2.1「40 个仪器测试」→ 89 条**（:library 82 + :app 7），「40」是 v0.8 时的旧数。⑤ **§9.4 第 1 条措辞订正**——「§12.4 中唯一明确未实现的功能项」在 v0.17 补了三条未落地门禁后已不成立，改为「§12.4 门禁之外唯一」，并补录 PRD 另有两项对外能力未实现（「获取指定位置的日程」「刷新当前时间线」——本文 §6 的任务拆解从未覆盖它们）。⑥ **§9.1 M6 行、§9.5 R8/Java 行、AD-02 证据 ③ 的旧条数**（7/7、5/5、74 个）一律加「当时」标注并指向现值。**PRD v1.13 侧的配套修订**（已另行核对）：补「实现状态」与两项未实现能力、§12.4 加落地列、修 4 处内部矛盾（§7.7.2「单击清除描边」与实现相反、§10.2 标题「三层」对四层表、§7.7「两样」对 §11.1 三项、§7.9 示例第 4 行与第 1/5 行及 expandRight 矛盾）、修 4 处引用错误（G4 的 100 条、§12.1 基准声明、§9.2 事件 5→8 类、§12.2 模块列举漏 :r8test）、§14.3.1 demo 行号订正。**README 与 AGENTS.md 同步对齐**：README 门禁状态表原停在「全库覆盖率未达标/死代码部分/R8 待验证/100 个用例」，已重写为当前 10 项实测，工程结构补 
8test/，文档链接 AD-01~AD-23 → AD-27；AGENTS.md 的「工程骨架阶段尚未开始实现」与「组件已实现到 M6」两处自相矛盾，合并为分里程碑真实进度，PRD v1.5/1345 行 → v1.13/1474 行，技术方案 v0.9/AD-01~AD-23 → v0.18/AD-01~AD-27，「《技术方案文档》尚不存在」订正，包名补 
8test，覆盖率改实测值，并补录 §12.4 三项无自动化落地的门禁。**表单方案**状态由「草案，待实现」改为「已定稿并已实现」，新增 §10 落地状态表逐项打勾，并点名唯一未落地项（内置按钮）。**一条与本次无关的已知残留**：library/build.gradle.kts 中 erifyAllCoverage 的 KDoc 仍写「分母从 17556 降到 3558，基准 88.79%」，属代码注释，本次按「不动代码」未改 | — |
| v0.19 | 2026-10-10 | **补齐两项「声明了却没有任何代码」的对外能力：eventAt / 
efreshNow（AD-28 / PRD v1.14）**——这是同一类缺陷第二次出现（第一次是 AD-24 的 utoLocateOnFirstShow：attrs 与 Config 齐备、有测试断言合并、唯独没人读；这次更早，连声明都没有，只有 PRD §11 两行字）。产品已拍板「补实现」而非删需求。① **eventAt(x, y)** 按视口坐标取日程，**与用户触摸共用同一份 hitTester.hitTest**（含 FI-015 的 48dp 热区扩展），不另写几何比对——两套规则必然漂移，且漂移是隐性的；返回 EventDetail，复用 detailOf()；零副作用（不动选中态、不进编辑态、不发回调）。② **
efreshNow()** 与 30 秒定时器共用抽出后的单一执行体 	ickNowOnce()，两条路径零差异；不节流；不覆盖 setNowMinute 固定过的时间。③ **11 条真机用例**（DayTimelineViewEntryPointTest），其中 eventAtAgreesWithRealTouch 逐点比对「eventAt 结论」与「onTouchEvent 派发的点击对象」，把「不能另写一套」变成断言。**两次回滚验证**：
efreshNow 改回空实现 → 4 条转红；eventAt 改回恒 null → 2 条转红。④ **顺带修掉一个门禁自身的缺陷**：
egisterCoverageGate 的非结构分支用 getElementsByTagName 扫全文档的 <counter>，而 JaCoCo 的 XML 里 class / method / sourcefile / package 四级各带一个 INSTRUCTION counter，同一份指令被累加四遍，核心门禁因此打印 9864/10744，报告里 class 级真实值是 2466/2686。百分比同比缩放所以看不出来，但打印值无法与报告对上。已改为只取 class 元素的直接 counter 子节点，打印值回归 2466/2686，**百分比不变（91.81%），门禁判定不受影响**。⑤ **LargeClass 阈值两难：没有调数字，改为拆类**。新增两个入口后 DayTimelineView 越过 	hreshold: 900。本仓 detekt.yml 的 TooManyFunctions 注释明写「真正的风险由 LargeClass 把关」——调它等于把刻意保留的防线撤回，且是典型的「贴着现状定数字」。改为把 SavedState（状态保存载体，只读写三个字段）与 EditSession.toDraft() / 	oResult()（纯映射，不碰视图状态）提为文件级私有声明，类回落到阈值内，detekt 0 违规，**阈值一个未改**。⑥ PRD 侧 R10 评级由中/中上调为高/高（v1.14，产品已拍板）：风险已从「开发期可能返工」变成「M1–M5 完成、M6 只差交付文档」形势下若不干预就会发生的发布前事故。⑦ **一条必须写明的局限**：仪器测试与回滚验证都在真机完成（93/93），拆类后**先补了回归测试并做完回滚验证**——第一版走 
estoreHierarchyState，回滚测试显示 93 条全绿、测试根本没咬住：Android 同进程内传递 SavedState 是**对象引用**，不走 Parcel 序列化。改为真 Parcel 往返（marshall/unmarshall/createFromParcel）后同一处破坏 4 条转红；SavedState 随之由 private 改为 internal（见 AD-28「一次写完就绿被回滚戳穿」）。**最终真机结果**：Pixel_XL_API_34 / Android 14 上 :library 102/102（新增 5 条 Parcel 往返）、:app 7/7、:r8test 混淆变体 8/8 全绿；API 26 模拟器上同样跑过 102 条，结论一致 | — |
| v0.20 | 2026-10-11 | **首次对外发布，并如实记录「发布成功 ≠ 发布物正确」**（纯文档修订 + 新增 `LICENSE`，**一行代码未动**，故未重跑门禁——无代码变更时门禁结果无变化，不以「跑过了」充数）。背景：M6 首次产出可下载产物，发布链路本身首次走通，但**产物内容是陈旧的**。① **DL-07 交付**：根目录新增 `LICENSE`（Apache-2.0）。正文 1–202 行**取自 apache.org 官方原文并逐字节校验**（SHA256 `CFC7749B…523D30`）而非凭记忆书写——许可证文本记错比不写更糟；文末按附录指引追加已填写版权人信息的声明（`Copyright 2026 kevinvane`），满足 PRD §18 DL-07「须完整填写版权人信息」。② **首个 JitPack 版本已发布但不可用**：tag `1.0.0-beat` 打在 `dd9e606`（2026-10-09），**落后 `master` 28 个提交**。已下载 AAR 解包核对 `classes.jar`——`EditDraft` / `EditResult` / `EventDetail` 三类不存在，`eventAt` / `refreshNow` / `enterEditMode` / `enterEditModeAndNotify` / `clearSelection` 五方法不存在，`MinuteOfDay` 仍是 `@JvmInline value class`（OQ-7 的 Java 接入能力全无，**Java 业务方连接口都实现不了**）；随包 `proguard.txt` 亦为该提交旧版，缺三个新类的 `-keep`、缺接口 `$DefaultImpls` 保留、`MinuteOfDay` 仍是 `-keepnames`——**开混淆的接入方会运行崩溃**。这正是 §12.5 / T7 建立 `:r8test` 的原因所在：这两类缺陷**只有「以业务方身份在混淆产物上跑」才看得见**，源码审查与 CI 绿灯都不会报错。③ **订正两处「库尚未发布」的过期陈述**（AD-22 与本文 §4.3 的「破坏性变更」段）：原句「库尚未发布（M6 未完成），现在改代价最小」已不成立——`1.0.0-beat` 的 tag **早于** AD-22 落地（提交 `594e7f5`），故该签名变更对现有接入方**无影响**，但**「未发布、代价最小」的窗口已经关闭**，此后任何签名变更都构成破坏性变更。④ **README 按开源库规范重写**：补徽章、目录、兼容性、版本与发布状态、55 项配置全表（核实 `TimelineConfig` 为 55 字段，非 `library/build.gradle.kts` KDoc 所写 53）、四层定制、回调参考、混淆、无障碍、已知限制、参与贡献等章节；**修 5 处事实性错误**（小节编号断档 3→5→6、门禁表两行粘连致渲染破损、技术方案版本引用停在 v0.18、`Agents.md` 引用错误、技术方案 AD 编号引用过旧）。⑤ **两条须写明的局限**：仓库根目录**无 `jitpack.yml` 亦无 `maven-publish`**，实测 JitPack 会自行识别 Android library 模块并注入 `maven-publish`、自动选用 JDK 17，**消费者不受影响**，但维护者本地跑不了 `publishToMavenLocal`；JitPack 构建执行 `clean publishToMavenLocal -xtest -xlint`，**不跑门禁**，门禁只由 CI 把关 | — |
| v0.21 | 2026-10-11 | **重打 tag 发布 `1.0.0-rc1`，并把「发布后核对产物」固化为流程**（纯文档修订，**一行代码未动**，故未重跑门禁）。v0.20 记录了 `1.0.0-beat` 产物陈旧且「须从 `master` 重新打 tag」，本次即执行该动作并记录结果。① **发版前跑可离线门禁**：detekt 0 违规、`verifyNoDeadCode` 0 处、`verifyCoreCoverage` 91.81%（2466/2686）、`verifyAllCoverage` 88.60%（3310/3736）、`lintDebug` 通过（`warningsAsErrors = true`）、`:r8test:verifyKeptSymbols` 10 个契约类全保留（APK 3936 KB）。**仪器测试未跑**——无代码变更时其结果不可能变化，按 v0.17 起的惯例不以此充数。② **tag 与推送**：`1.0.0-rc1` 打在 `95d081c`（= 本次 README/LICENSE/文档修订后的 HEAD），选 `rc` 而非 `1.0.0` 是如实反映 M6 未完成（M0 色值未定稿、DL-03~DL-06 未齐）；同时删除远端与本地 `1.0.0-beat`。③ **发布后核对产物——本条是本次最值得固化的经验**：JitPack 返回 `ok`、CI 全绿，**两者都不代表产物内容正确**。解包 `1.0.0-rc1` AAR 逐项核对：`EditDraft` / `EditResult` / `EventDetail` / `EventDetailKt` / `EditDraftKt` 五个类在（`1.0.0-beat` 缺前三个）、`detailOf` / `eventAt` / `enterEditMode` / `enterEditModeAndNotify` / `clearSelection` / `refreshNow` 六个方法在（后四个 `1.0.0-beat` 缺）、`MinuteOfDay` 已无 `boxed-impl`（value class 标志）确为普通类（OQ-7 的 Java 接入能力落实）、`proguard.txt` 21 条保留规则与仓库 `consumer-rules.pro` 一致。**结论：现有七项自动门禁没有一项能发现「打 tag 的提交不是最新提交」或「产物与源码不同步」**——这类问题只能靠解包核对发现，已写入 README 的发布者须知。④ **一条实测纠正**：v0.20 曾写「JitPack 会从 GitHub 根目录读取许可证并随 AAR 分发」，**实测不成立**——`1.0.0-rc1` 的产物目录只有 `.aar` / `.module` / `.pom` / sources.jar 与校验和，**无 LICENSE**。AGP 不把许可证打进构件，JitPack 也不附带；接入方若重新分发需自行附带。README 已订正 | — |
| v0.22 | 2026-10-11 | **清除已废弃版本在正文中的引用**（纯文档修订，一行代码未动）：v0.20 的 §AD-22「破坏性变更」段以 `1.0.0-beat` 为依据说明「现有接入方不受影响」，而该版本已于 v0.21 删除，这个依据随之失效——读者会去找一个已不存在的版本。改为直接以在世版本陈述：**唯一在世的 `1.0.0-rc1`（tag `95d081c`）即含这些新签名，本次变更从未以「缺这些签名」的形态对外发布过**。结论方向不变（「未发布、改动代价最小」的窗口已关闭，此后签名变更即破坏性变更）。README 同步删除整个「维护者须知」小节，并把「JitPack 返回 `ok` 不等于产物正确」的告诫移入版本状态表。变更记录表内 v0.20 / v0.21 两行**按历史原样保留**，不回改——变更记录记的是当时发生了什么 | — |
| v0.23 | 2026-10-12 | **M0-1 色值定稿落地**（对齐 PRD v1.18）。① 设计侧定稿交付到位，17 项浅/深两套共 34 个取值已全部替换（`docs/DayTimeline-设计侧定稿交付.md`），**AD-12 占位状态解除**，§6 M0 章节与 §9.1 实施进度同步更新。② 与 v0.17「替换色值零返工」的判断一致——组件代码一行未动，只改两份 `colors.xml`。③ **OQ-1 关闭**；PRD §18 R10 由「高/高」降为「低/低」并标记解除；§16.2 M6 出口硬卡点「17 项色值全部为设计定稿值」通过。④ **Q7 按定稿色值重走查**（此前占位值下判不了）：逐对计算 WCAG 对比度 + 真机渲染像素采样核对，**保留 1 项偏差**——浅色已过态文字 2.63:1。M5 状态由「实现完成，验收待 M0」改为「已完成」。⑤ M0-2 / M0-3 仍无交付。⑥ **色值出口标准的自动化已补上**：新增 :library:verifyColorsFinalized（色名集合 / 取值须等于设计定稿 / 对比度三项检查），接入 CI 静态门禁；回滚验证过「改回占位值」与「深色漏配色名」两条均失败 | — |
| v0.24 | 2026-10-12 | **品牌强调色由紫改蓝**（对齐 PRD v1.20）。设计侧第二版定稿把主强调色统一到蓝色家族，34 个取值再次全部替换，组件代码仍零改动。Q7 已按蓝色系重走查并同步更新 §9.1 M5 行的实测值。保留偏差随改版变差（浅色已过态 2.63:1 → 2.51:1），`verifyColorsFinalized` 的 `designFinalColors` 登记表与 `acceptedContrastDeviations` 注释同步更新 | — |
| v0.25 | 2026-10-13 | **§12.1 性能指标的可自动化边界落地（AD-29）**（对齐 PRD v1.21）。§12.1 九项指标此前**无一项**能自动判定，§12.4「性能基准」长期只能记「部分落地」。① 新增 `:library:verifyNoChildViews`：扫 12 个创建/挂载标记 + 全部布局资源，**对话框不拦**（独立 window、不常驻），接入 CI 静态 job，无需设备；运行时一侧由新增仪器测试复核。② 新增 `PerformanceBenchmarkTest` 4 条纯 JVM 用例（布局 200 条、增量更新 100 条、以及一条 §12.1 原文没有的**增长形状**门禁）。③ 新增 `library/src/androidTest/AndroidManifest.xml` 与宿主 `TimelineTestHostActivity`——**此前 102 条仪器测试全部是「裸 Context + measure/layout」，组件从未挂进窗口，那种 View 上的 `invalidate()` 不调度任何绘制，「刷新是否收敛」在原有基础设施下原理上无法观测**；新增 3 条用例改用 `ViewTreeObserver.OnDrawListener` 数真实帧数。④ **四处回滚验证全部实测**，其中两处结论与直觉相反，已写入用例 KDoc：注入子 View → 源码门禁红；列分配由贪心首次适配换成两两比较 → **形状门禁红（2.06 → 3.66）而三条绝对耗时用例仍全绿**（真实分布 0.064ms → 1.10ms，仍在 4ms 预算内）；`requestRefresh` 去掉签名比对 → 收敛用例红（1 → 2 帧）；定时器改 200ms 无条件重绘 → 静止用例红（2 秒内 1 → 11 帧）。⑤ 覆盖率由 91.81% / 88.60% 升至 **93.34% / 89.61%**，仪器测试 102 → 108 条，demo 7 条与 R8 混淆变体 8 条均复跑全绿。⑥ **6 项确认无法自动化**（首次显示中位数 / P95、滚动掉帧率、拖拽跟手、内存、耗电），理由与「为什么模拟器上卡掉帧率只会得到假门禁」见 §9.2.2；§9.4 新增第 8 条登记其**人工采集流程**尚待固化 | — |
| v0.26 | 2026-10-13 | **订正 §9.4 第 1 条的过期陈述**（纯文档修订，**一行代码未动**，故未重跑门禁）。① 该条原文末尾写「另有两项对外能力亦未实现（PRD §11.1『获取指定位置的日程』、§11.2『刷新当前时间线』），本表 v0.17 核对时补录」——**该句早已不成立**：两项在 PRD v1.14 即按产品决议补齐实现（`eventAt(x, y)` / `refreshNow()`，见 AD-28），落地证据见 §9.5。② **顺带核对了一处「看起来同样过期、实际不是」的**：AD-28 的背景表（§6）仍写「只有内部 `HitTester.hitTest`，无对外入口」——但该表位于「**背景**：v0.18 以项目现状对齐文档时查出」之下，记的是 v0.18 当时的事实，**不动**；把正当的历史记录当成过期陈述去「修」，等于抹掉证据链。③ `Agents.md` 第五条教训里同一句陈述（「`eventAt` / `refreshNow` 两个方法根本不存在」）已加「当时」标注并指向 AD-28，与本文 v0.17 起的惯例一致——教训章节记的是事件，不是现状，不加标注会被后来者误读为当前状态。④ 本文的「对应 PRD」一行停在 v1.14，一并补到 v1.21 | — |
| v0.27 | 2026-10-13 | **PRD v1.22 产品决议落地：内置「完成/取消/删除」按钮确定不做**（对齐 PRD v1.22 删项）。本项自 v0.17 起挂在 §9.4 第 1 条，现关闭。① **§9.4 第 1 条改为已完成并划掉**，四条要点留档（警示注释、刻意不声明该属性、配置两字段已移除、以及**决议理由中最硬的一条与 AD-01 直接冲突**：内置按钮要引入子 View 与浮层容器，会打破 §12.1「常驻界面元素数 = 0」，而那条正是 `verifyNoChildViews` 守着的）。② **AD-17 末段的理由必须改**——原文写「未映射的属性……实现按钮时一并加回」，理由是「能力缺口」，现在需求已删项，**不会再加回来**；留着「一并加回」等于给后人留一条假线索。③ 五处代码注释同步改为「已决议不做」，其中 `DocsConfigCoverageTest` 的 KDoc 尤其关键：它当初把 `dtShowEditActions` / `dtShowDeleteAction` 从文档里删掉的理由写的是「能力缺口」，**理由随需求删项一起失效**，须改为「该需求已取消」——否则下次有人按旧理由再加回去。④ 接入方三份文档（README / 完整能力文档 / 交互说明文档）与表单输入方案的「唯一未落地项」表格同步订正。⑤ **不降低任何验收标准**：取消的是一项功能需求，其余判定原样；本项也不再计入《质量报告》§1.2 的发布阻断 | — |
| v0.28 | 2026-10-13 | **依赖漏洞扫描门禁（AD-30）：OQ-2 关闭，§12.4「已知高危漏洞 0 个高危」由人工转为门禁**（**但尚未跑通**，见下）。① 新增 `verifyNoHighVulns`（OWASP Dependency-Check 13.0.0，阈值 **CVSS ≥ 7.0**）与 CI 独立 job `dependency-scan`（含 NVD 漏洞库缓存、报告归档、失败时打印 CVSS ≥ 7.0 的漏洞明细）。② **OQ-2 关闭**：K10「零第三方依赖」未区分运行时与构建期，本次以「引入构建期工具」的方式落定为**运行时零非 AndroidX 第三方依赖、构建与测试期允许工具类依赖**——该插件不进入任何模块的运行时 classpath，§12.2 针对的发布物不受影响。③ §9.4.1 记录了三个坑（13.x 的 DSL 全换、Kotlin DSL 不生成访问器、13.x 强制 NVD API 且强制要 key）与一个「门禁形同虚设」的坑，均本机实测。④ **必须如实记下当前状态：门禁一次都没真正执行过**——无 NVD API key 则连漏洞库都下载不下来，前置检查会直接拦下。§9.2 门禁表写的是「已建立门禁，尚未跑通」，**不是「已达成」**。⑤ 本次唯一的外部前置：免费申请 key（https://nvd.nist.gov/developers/request-an-api-key）配成环境变量 `NVD_API_KEY`、CI 配成同名 secret | — |
| v0.29 | 2026-10-15 | **M0-2 / M0-3 交付，M0 设计定稿三项齐备**（对齐 PRD v1.23）。① 设计侧交付交互标注与切图/字体资源（`docs/DayTimeline-交互标注与字体资源交付清单-设计回复.md`）。② **M0-2** 交互标注经核对与现有实现一致、**组件代码无改动**；设计回复对「选中态字重」的表述与 PRD §7.4 有疑义（#3 称标题默认 Medium，#6 称选中态 Regular→Medium），按 §7.4「选中态色 + 轻微加粗」定为**常规 Regular、选中 Medium**。③ **M0-3** 字体规格落地 `Theme.kt`：新增内部 `TimelineTypeface`（`sans-serif` / `sans-serif-medium`），`axisLabel`→Regular、`nowLabel`→Medium、块标题默认 Regular（选中态临时切 Medium）、副标题恒 Regular，副标题行高由 1.2× 改为 16sp/12sp；新增仪器测试 `textPaintsUseFinalizedTypefaces` 锁住字族映射，**回滚验证会转红**。切图判定「无位图需求」，组件维持全自绘。④ §6 M0 章节、§9.1 M0 行、AD-12 遗留栏、§9.4 第 4 条同步更新；《质量报告》§1.2 发布阻断项由 5 → 4。⑤ **不降低任何验收标准** | — |
| v0.30 | 2026-10-15 | **修掉极短日程块内文字溢出块外的绘制缺陷**（**对外签名一行未动**，但新增内部方法 `Geometry.naturalBlockHeight` 且行为有变，故记入）。背景：`:app` demo 的 19:00–19:02「提醒」在真机上文字溢出到块外。**根因**：`DayTimelineView` 的 `tooShortForText` 判据写成 `blockH < dimens.blockMinHeight`，而 `blockH` 早已被 `Geometry.blockHeight` 用 `coerceAtLeast(minBlockHeight)` 抬到最小值，条件恒 false——「块高不足则跳过文字」这条降级路径**从未生效**，接入方文档（DL-04 / DL-06）声称的行为与实现不符，属「声明了却没有代码」（教训第五条）的又一实例。① **修法**：`Geometry` 新增 `fun naturalBlockHeight(startMinute, endMinute, hourHeightPx): Int` 返回**未兜底**的原始高度，`blockHeight` 重构为 `naturalBlockHeight(...).coerceAtLeast(min)`（**`hourHeightPx <= 0` 的 early return 必须保留**，否则 `GeometryTest`「格高为 0 时不崩溃」会失败——已踩过并修复）；`tooShortForText` 改用 `naturalBlockHeight(startMin, endMin, hourHeight) < dimens.blockMinHeight`，与 PRD §7.2「最小显示高度 20dp：低于此值无法显示内容」一致。② **回滚验证**：改回旧判据 → 新增仪器测试 `shortEventIsFlaggedTooShortForTextInsteadOfOverflowingTheBlock` 转红（记录 `{ping=false, long=false}`），已还原。③ **真机复核**（Pixel / Android 9）：19:00–19:02 块现只绘色条与强调边、文字不再溢出。④ **未采用 `Paint.fontMetrics`「两行是否放得下」判据**——会在 font_scale 200% 下让 1 小时块也丢文字，与《质量报告》§4.2.2「200% 下 66 分钟块文字完整未溢出」的手工记录冲突。⑤ **不改任何对外签名 / 验收标准** | — |
| v0.31 | 2026-10-15 | **订正 v0.30：极短日程判据改用「块高是否放得下块内要画的文字行数」**（**对外签名未动**）。v0.30 把判据从死条件 `blockH < blockMinHeight` 改为 `naturalBlockHeight < blockMinHeight`，只覆盖了 < 20dp 的块；真机复查（Pixel / demo 的 `16:40–17:00` 20 分钟块）发现**高于 20dp、仍放不下两行的块照样溢出**——20 分钟块原始高度 21.3dp（64dp 格高）越过 20dp，但要画「时间 + 标题」两行塞不进 56px，副标题「并行任务 C」画到了块背景外。① **新增 `paint/BlockTextFitting`**：用块文字 `Paint` 的 `fontMetrics` 算第 n 行基线，`requiredHeightPx = paddingVertical - ascent + (n - 1) * 行距`；`DayTimelineView` 据此设 `tooShortForText`（显示副标题按两行、否则一行），与默认绘制共用同一支 `Paint`，杜绝两处各算一套后漂移。② **判据口径的取舍**：只保证**最后一行基线落在块内**（不再额外留 descender）——Pixel 实测 30 分钟块（84px）第二行基线正好落在块内、视觉完好，算上整段 descender 会把它误判为放不下。③ **回滚验证**：判据改回 `blockH < dimens.blockMinHeight`，新增断言 `c20`（20 分钟块）与 `ping` 立刻由 true 变 false，用例转红（记录 `{c20=false, ping=false, long=false, b40=false}`）。④ **真机全时段扫描**（Pixel / Android 9）：30/40/45/60 分钟块两行均在块内，20/5/2 分钟块无文字，无溢出、无回归。⑤ 接入方文档（DL-04 / DL-06 / 主题定制指南）的「两行文字」措辞同步改为「要画的文字行数」。**不改任何对外签名 / 验收标准** | — |
| v0.32 | 2026-10-17 | **漏洞门禁首次在 CI 执行完成，并据此收窄扫描范围（对齐 PRD v1.24）**（`build.gradle.kts` 加一行 `scanConfigurations`，对外签名一行未动）。① **首次跑出报告**（OWASP Dependency-Check 13.0.0，NVD 数据 2026-10-17 06:31Z，`project ':app'` 305 个依赖）：命中 **70 个 CVE、51 个 CVSS ≥ 7.0**，任务失败、报告靠 CI `if: always()` 归档。② **逐条核对后全部命中落在构建期工具链**——AGP 的 `_internal-unified-test-platform-*`（grpc / netty / protobuf）与 Kotlin/detekt 的 `kotlinCompilerClasspath` 等（`kotlin-compiler-embeddable`），**运行时依赖 0 命中**。这些坐标不在 `gradle/libs.versions.toml`、也不进 AAR/APK（OQ-2 的「构建/测试期允许工具类依赖」）。③ **据此刻意设 `scanConfigurations = [debugRuntimeClasspath, releaseRuntimeClasspath]`**（白名单，非 `skipConfigurations` 黑名单）；本机验证收窄后扫描集（:app 60 / :library 58 / :r8test 58 个文件）0 个工具链命中。**§9.4.1 由此新增第 4 个坑**：AGP 那批配置名里的 `-test-` 两侧是连字符，匹配不上插件的 test 正则，默认 `skipTestGroups` 挡不住它。④ **收窄后的「0 个高危」仍待 CI 重跑确认**（本机到 NVD 出口限流，不能在本机复跑）。⑤ 同步 §9.1 门禁表、§9.2、§9.4、§9.4.1，《质量报告》§1.2 / §4.4 / §6 / §8 与操作手册。**不放宽任何验收标准**（§12.4 仍要求 0 个高危） | — |
| v0.33 | 2026-10-17 | **长按「超时即进入编辑态」并加拾起反馈（对齐 PRD v1.26，AD-31）**（`GestureArbiter` / `DayTimelineView` 内部改动，对外签名未动；新增两条仪器测试与三条 JVM 单测）。① **进入时点**：`GestureDetector.onLongPress` 命中已有日程块时**立即**进入编辑态（超时取 `ViewConfiguration.getLongPressTimeout()`，约 400–500ms，**不提供按组件配置的入口**），不再等 `ACTION_UP`——对齐 Google 日历「拾起后不松手即可拖」。② **`GestureArbiter` 新增 `markEditing()`**：超时进入后把 `editing` 置真，后续位移才判 `DragMove` 并消费手势（否则会被判 `Scroll` 让给外层容器，出现「编辑态在、块不动」）。抽独立方法而非改 `onLongPressTimeout` 返回语义，以保留「命中空白处超时仍 `editing=false`」。③ **抬手不再重复进入**（`Intent.LongPress` 分支直接 return）、**`ACTION_CANCEL` 保留编辑态**（超时时已真实进入，被打断不等于取消；D3 不变）。④ **拾起反馈**：`performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)` + 被编辑块**原位淡出为幽灵**（`Canvas.saveLayerAlpha` 淡化既有绘制），**不引入任何新语义色**（17 项 × 深浅两套取值不变，`verifyColorsFinalized` 不受影响）；幽灵绘制抽为文件级私有 `paintBlock(...)`，把类体压回 `LargeClass` 阈值内。⑤ **验证**：新增宿主窗口仪器测试 `LongPressPickupTest`（按下 → 等超时 → 断言**手指未抬起**时已进入编辑态）与 `DayTimelineViewEditTest.editedBlockFadesToGhostAtItsOriginalPosition`（逐像素断言原块被淡化）、`GestureArbiterTest` 三例；**关键用例均已回滚验证会转红**（改回「抬手才进入」→ 长按用例 2 条转红；去掉幽灵淡化 → 幽灵用例转红）。⑥ 同步 PRD §8.2 / §8.2 附注 / FI-004 / §19.4 v1.26，《交互说明文档》《完整能力文档》。**本次不降低任何验收标准** | — | 研发 |

---

## 9. 实施进度回写

### 9.1 当前状态

| 里程碑 | 状态 | 说明 |
|---|:--:|---|
| M0 设计定稿 | **已完成**（v0.29 更新） | **三项齐备**：M0-1 色值 34 个取值已由设计交付并替换（`docs/DayTimeline-设计侧定稿交付.md`），AD-12 占位状态解除；M0-2 交互标注、M0-3 切图与字体资源已交付（`docs/DayTimeline-交互标注与字体资源交付清单-设计回复.md`）——M0-3 字体规格已落地 `Theme.kt`，切图判定「无位图需求」。Q7 / FC-002 已随定稿色值重走查 |
| M1 工程基线 | **已完成** | `:app`→`:library` 接线、res 骨架、`androidx.customview` 依赖、lint 严格配置、CI（`.github/workflows/ci.yml`）、JaCoCo 覆盖率门禁（核心 + 可测逻辑）、detekt 与死代码自动阻断（`verifyNoDeadCode`）、R8 混淆消费端验证模块 `:r8test` 均已建立。**M1-8（R9 结论实测）已完成**（2026-10-10），证据见 AD-02 |
| M2 静态呈现 | **已完成** | `core/` 全部算法 + U1–U16 逐条验收（20 个用例）、网格与日程块绘制均已完成 |
| M3 数据与滚动 | **已完成** | 数据契约、提交/单条更新/单条删除/切日期/指定当前时间、增量 diff + 滚动锚点、刷新收敛、30 秒定时器与生命周期摘除已完成；**两种滚动模式均已实现**——自身模式消费手势并执行惯性滚动，外部模式 `onMeasure` 按全天内容高度测量且 `Intent.Scroll` 分支 `return false` 让给外层（E29）。**FI-012 首次定位已于 v0.10 补齐**（此前 `autoLocateOnFirstShow` 无人消费，详见 AD-24） |
| M4 交互闭环 | **已完成** | `GestureArbiter` 手势状态机、`EditSession` 编辑态、`SnapCalculator` 上下对称吸附、48dp 热区手柄、`confirmEdit`/`cancelEdit`/`requestDelete` 与 `EditController` 第四层接管均已实现。**E20 自动取消已于 v0.11 与用户取消拉齐**（状态全清 + 发「编辑取消」，PRD §14.3.1），5 条新仪器测试真机全绿 |
| M5 打磨 | **已完成**（v0.23 更新：原记「实现完成，验收待 M0」） | 字体放大标签降密度、`onSaveInstanceState` 状态保存、运行时资源重载（深色/多语言）、无障碍虚拟视图均已完成。**Q7 已于 2026-10-12 按定稿色值重走查**：深浅两套逐对计算 WCAG 对比度，真机渲染经像素采样逐项比对，取值生效无偏差；常规文字 6.7:1~15.4:1、已过态深色 5:1 均达标。**保留 1 项偏差**：浅色已过态文字 2.63:1（§7.3 对该项仅给定性要求，未判不合格，已记录待设计复核）。FC-002 换肤验收随新色值同步重走 |
| M6 发布 | **部分** | **DL-07 许可协议已交付**（Apache-2.0 全文取自 apache.org 原文 + 版权人信息，符合 PRD §18 DL-07）；README 已按开源库规范重写；**开启混淆的消费端验证已完成**（`:r8test:verifyKeptSymbols` 10 个契约类全保留 + 混淆变体仪器测试 **8/8**，见 §9.2）。**`1.0.0-rc1` 已于 2026-10-11 发布**（tag `95d081c`），产物经解包逐项核对确认含 AD-22 / AD-23 / AD-28 全部 API、`MinuteOfDay` 已是普通类、`proguard.txt` 21 条规则与仓库一致，**可用**；选 `rc` 而非 `1.0.0` 是如实反映 M0 色值未定稿。**遗留两项**：① **AAR 内不含 LICENSE**（实测，AGP 与 JitPack 均不附带），重新分发的接入方需自行附带；② **DL-03 快速上手文档未交付**（DL-04 / DL-05 / DL-06 / DL-07 / DL-08 均已交付）——**DL-09 质量报告已于 2026-10-13 出草案**（`docs/DayTimeline-质量报告.md`），但**状态为草案、不可据此发布**：§12.1 六项性能指标与 §14.3 五项产品底线的真机走查一条未执行，§12.2 兼容矩阵中 RTL 一项**零自动化覆盖** |

### 9.2 门禁现状

| 门禁（§12.4） | 状态 | 证据 |
|---|:--:|---|
| 核心逻辑单元测试覆盖率 ≥ 90% | **达成** | `:library:verifyCoreCoverage` 实测 **93.34%**（2507/2686，2026-10-13；此前 91.81% / 2466/2686 —— 增量来自 `PerformanceBenchmarkTest` 走了 `EventSanitizer` / `EventDiff.anchorOf` / `restoreOffset` / `TimeStateResolver` 的默认参数组合）。**计数口径 v0.19 已修**：此前该分支扫全文档 `<counter>`，把同一份指令数了四遍，打印成 9864/10744——百分比同比缩放所以看**不**出来，但打印值与报告对不上。已改为只取 class 级 counter，见 AD-28 |
| 平台规范检查 0 错误 | **达成** | `:library:lintDebug` 通过，0 error / 0 warning（`warningsAsErrors = true`） |
| 代码风格检查 0 违规 | **达成** | `:library:detekt` + `:app:detekt` 均 0 违规（53 → 0），配置见 `config/detekt/detekt.yml` |
| 死代码 0 处 | **达成** | `:library:verifyNoDeadCode` 专项门禁，实测 0 处；豁免清单见 `config/detekt/EXEMPTIONS.md` |
| 静态代码检查 0 严重 | **达成** | 同 detekt |
| R8 混淆后功能正常 | **达成** | `:r8test:verifyKeptSymbols`（10 个契约类全保留）+ `:r8test:connectedReleaseAndroidTest`（混淆变体仪器测试 **8/8**，Pixel / Android 9，2026-10-10，含 OQ-7 方案 A 的 Java 调用方用例 `javaCallerCanImplementContractAfterMinification`）。**该用例首次运行即抓出 `consumer-rules.pro` 里 `MinuteOfDay` 只保类名不保成员的缺口**（`NoSuchMethodError: getMinuteOfDay()I`），已修复。**v0.15 起 D3 断言改为白名单式**（`RecordingListener` 记录全部 8 个回调，`assertEquals(listOf("cancelled"), fired)`），并新增对照组 `confirmStillEmitsDataChangeAfterMinification`——反向验证：往取消路径注入一个多余的 `onEventCreated` 后该用例转红，旧版 `assertNull(createdRange)` 断言在同一注入下**会通过** |
| 仪器测试（L2） | **达成** | `:library:connectedDebugAndroidTest` **108/108 全绿**（Pixel_XL_API_34 / Android 14，2026-10-13）。含 AD-25 的 5 条 E20 用例、AD-26 的 3 条 `ACTION_CANCEL` 用例、3 条外部滚动模式取消用例、2 条「接管态下完成事件互斥」用例、AD-27 的 5 条、AD-28 的 16 条、**以及 AD-29 的 3 条**（`RedrawConvergenceTest`，2026-10-13 新增，见 §9.2.2） |
| 示例应用仪器测试 | **达成** | `:app:connectedDebugAndroidTest` **7/7 全绿**（`MainActivityCancelPathTest`）。**2026-10-10 新建，此前 `app/src/` 只有 `main`**——demo 侧两条取消收尾只靠注释自证；已接入 CI |
| 全库覆盖率 ≥ 75% | **达成（口径已修订）** | `:library:verifyAllCoverage` 实测 **89.61%**（3348/3736，2026-10-13；此前 88.60% / 3310/3736）。**口径与 PRD 原文不同**，见下方说明 |
| 已知安全漏洞 0 个高危 | **2026-10-17 CI 首次执行完成，已收窄到运行时 classpath** | `verifyNoHighVulns`（OWASP Dependency-Check 13.0.0，阈值 CVSS ≥ 7.0，`dependencyCheckAggregate` 汇总 `:library` / `:app` / `:r8test` 三模块）+ CI 独立 job `dependency-scan`（带 NVD 漏洞库缓存、报告归档、失败时打印漏洞明细）。**2026-10-17 首次在 CI 跑出报告**：未收窄范围时命中 70 个 CVE（51 个 ≥ 7.0）**全部落在构建期工具链**（AGP 的 `_internal-unified-test-platform-*`、Kotlin/detekt 的 `kotlinCompilerClasspath` 等），运行时依赖 0 命中。据此设 `scanConfigurations = [debugRuntimeClasspath, releaseRuntimeClasspath]`（OQ-2 口径）。**收窄后的「0 个高危」仍待 CI 重跑确认**（本机到 NVD 出口限流，不能在本机复跑） |
| 性能基准（§12.1 全部指标） | **部分落地（较 v0.19 有实质推进）** | §12.1 九行指标中 **3 行已自动阻断**：全量布局 200 条 ≤ 4ms、列表增量更新 100 条 ≤ 16ms、网格区常驻界面元素数 = 0（源码扫描 + 运行时双侧）；另有两道相邻守卫——布局增长形状（§12.1 原文没有，防复杂度回退）、D17 刷新收敛（3 条仪器测试，需设备）。**仍只能人工真机走查的 6 项**：首次显示中位数 / P95、滚动掉帧率 ≤ 3%、拖拽跟手 ≤ 1 帧、内存 1 小时平稳、1 小时耗电——它们都必须真机 + 真滚动才能测，模拟器帧时序不代表任何真实设备。详见 §9.2.2 |
| 文档完整性（无遗留待办标记） | **未落地** | PRD §12.4 要求，但无任何工具或 CI 步骤扫描文档中的待办标记，只能人工核对 |
| 界面自动化冒烟 | **部分落地** | `:app` 已有 7 条取消链路仪器测试（v0.12 起接入 CI）；表单提交、日期切换、配置面板、删除成功路径仍无自动化，见 §9.4 第 7 条 |

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

> **表中数字是 v0.8 修订时的实测快照**（2026-10-09）。截至 2026-10-10 的最新一次
> `jacocoTestDebugReport`（15:27，晚于全部源文件）为：`core` 2466/2686、`api` 1035/1929、
> `paint` 0/1498、`internal` 0/985、根包 0/4036，合计 11134 条，结构上拿不到 JVM 覆盖率的
> 仍是后三者。**当前生效的门禁值见 §9.2 表**（91.81% / 88.60%）。

修订后的口径：**「JVM 可测逻辑的覆盖率」**，被排除的三层由 **89 条仪器测试**（`:library` 82 + `:app` 7）与真机走查覆盖。

**但必须如实记录这条门禁的局限**：反向验证显示，往 `EditResult` 注入 5 段共 119 条真实的未测逻辑，覆盖率仅从 88.79% 降到 85.91%，**仍在 75% 之上**。即百分比门禁在基准远高于阈值时对少量漏测天然不敏感。

因此本门禁的定位是「抓大幅退化」，而非「抓漏测」。**防漏测靠的是结构性断言**，例如 `TimelineConfigTest.mergedWith 覆盖全部可配置字段` 直接比对字段集合——新增字段忘合并即刻失败，不依赖任何阈值。该测试在本次修订中实测抓出了 `autoLocateOnFirstShow` 漏合并的真实缺陷（XML 配了会被 `setConfig` 静默丢弃）。

**产品结论**：接受修订后的口径，已回写 PRD v1.4 §12.4.1 并同步 G7 / T4 措辞。**未决**：是否需要把仪器测试的覆盖率数据并入统计（需引入 `jacoco-android` 等第三方插件，与 K10 的边界有关），本次不做。

#### 9.2.2 §12.1 性能指标的可自动化边界（AD-29，2026-10-13）

§12.1 的九项指标此前**没有任何一项**能自动判定——§12.4 的「性能基准」因此长期只能写「部分落地」。本节记录逐条对照代码后的结论，以及为此新建的三道门禁。

**能自动测的（已落地）**

| 指标 | 门禁 | 落地方式 | 本机实测 |
|---|---|---|---:|
| 全量布局耗时（200 条）≤ 4ms | `PerformanceBenchmarkTest` 2 条 | 纯 JVM（`core` 零 `android.*` 依赖），无设备 | 最坏 0.072ms / 真实分布 0.064ms |
| 列表增量更新耗时（100 条）≤ 16ms | 同上 1 条 | 同上 | 0.032ms |
| 布局增长**形状**（非 §12.1 原文） | 同上 1 条 | `T(400)/T(200)` 须 < 3.0 | 2.06 |
| 网格区常驻界面元素数 = 0（编译期一侧） | `:library:verifyNoChildViews` | 源码扫描 + 布局资源检查，进 CI 首个 job，无需设备 | 0 处 |
| 网格区常驻界面元素数 = 0（运行时一侧） | `RedrawConvergenceTest` 1 条 | 挂进真实窗口后遍历 View 树 | 0 个后代 |
| D17 无不收敛重绘 | `RedrawConvergenceTest` 2 条 | `ViewTreeObserver.OnDrawListener` 数真实帧数 | 0 帧 |

**不能自动测的（只能人工真机走查，且必须在 DL-09 里如实记为未自动化）**

首次显示耗时中位数 ≤ 100ms、首次显示 P95 ≤ 150ms（后者要求 ≥ 100 次采样，CI 做不到）、
滚动掉帧率 ≤ 3%、拖拽跟手延迟 ≤ 1 帧、内存 1 小时平稳、停留 1 小时耗电无明显上升。
**理由不是「没做」而是「测不到」**：这五项都要求真实设备上的真实绘制与真实滚动；
CI 的 `instrumented` job 跑在 API 34 模拟器上，模拟器帧时序不代表任何真实设备，
在它上面卡 ≤ 3% 只会得到一个必然误报的假门禁。

**必须一并记录的三条灵敏度结论（都做过回滚验证，不是推断）**

1. **绝对耗时门禁的 headroom 是 3.6–58 倍，中等程度的算法退化在它下面完全隐形。**
   把 `OverlapLayoutEngine.assignColumns` 的贪心首次适配换成「两两区间比较」后，
   真实分布由 0.064ms 涨到 1.10ms——**仍在 4ms 预算之内，用例保持绿色**。
2. **真正咬得住复杂度回归的是增长形状那条**：同一次退化下比值由 2.06 涨到 **3.66**，转红。
   因此 `PerfData.fixedOverlap` 把「最大同时重叠数」钉死为常数 2，
   否则条数翻倍时密度也翻倍，`O(n × 最大重叠数)` 的第二项跟着翻倍，比值会逼近 4——
   算法没退化也会红，那种门禁只会制造噪声。
3. **「完全同时」这组数据对算法退化最不敏感**：退化实现的内层循环在全部重叠时第一次比较就
   `break`，耗时几乎不变（0.072ms vs 0.072ms）；同一处退化在真实分布上涨了 17 倍。
   三组数据集因此必须并存：最坏情况卡上界，真实分布卡灵敏度，固定重叠卡增长形状。

**另一条反直觉的实测**：`RedrawConvergenceTest` 的两条收敛用例，把 `requestRefresh`
改成无条件 `invalidate()` 后只 +1 帧（不是 +50 帧）——Android 会把同一个主线程任务里的
多次 `invalidate` 合并成一帧。所以这两条用例断言的是「**有没有出现意外的一帧**」，
不是帧数多少；它们抓得住「自我触发的重绘循环」，抓不住「一帧里画了几次」。

**为此新建的测试基础设施**：`library/src/androidTest/AndroidManifest.xml` 与
`TimelineTestHostActivity`。此前 102 条仪器测试**全部**是「裸 Context + `measure`/`layout`」，
组件从未 attach 到窗口——那种 View 上的 `invalidate()` 不调度任何绘制，
`OnDrawListener` 一次都不会触发，因此「刷新是否收敛」在原有基础设施下**原理上无法观测**。
这正是本仓库第五类缺陷的成因：能力写在文档与 KDoc 里，没有任何东西守着。

### 9.3 已知豁免清单（§5.2 要求的显式清单，第一条）

| 位置 | 检查项 | 理由 | 复核时机 |
|---|---|---|---|
| `DayTimelineView.onTouchEvent` | `ClickableViewAccessibility` | 该检查只在 `onTouchEvent` 函数体内做直接调用扫描，无法穿透 `GestureDetector` 委托；`performClick()` 实际在 `enterEditByLongPress` 中调用，无障碍契约已满足。**精确豁免单条，非全局关闭** | 已随 M4 在真机复核（`performClick` 由长按进入编辑态的路径触发） |

### 9.4 后续优先事项

1. ~~**内置「完成/取消/删除」按钮**~~ —— **2026-10-13 关闭：产品决议不做**（PRD v1.22 删项，理由见 PRD §7.7）。要点留档：① 原 PRD §7.7 要求「可配置是否内置」，实施中一直没做——`attrs.xml` 有警示注释、`ConfigFromAttrs` 刻意不声明该属性（见 AD-17 末段）、`TimelineConfig` 的 `showEditActions` / `showDeleteAction` 两字段也已移除。② 移除那两字段时担心过它们会被当成「训练字段」误判违反 T5 死代码 0 处；结论是配置类的 public 字段不属于死代码范畴，门禁未受影响。③ **决议理由中最硬的一条与 AD-01 直接冲突**：内置按钮要引入子 View 与浮层容器，会打破 §12.1「网格区常驻界面元素数 = 0」——那条指标正是 `verifyNoChildViews` 门禁守着的。④ **本条不再计入任何待办或发布阻断**；三个出口由第四层 `EditController` 提供，demo 的 `MainActivity` + `EventDetailPopup` 为参照实现。

   另：本条原文末尾曾写「另有两项对外能力亦未实现（PRD §11.1「获取指定位置的日程」、§11.2「刷新当前时间线」），本表 v0.17 核对时补录」——**该句早已不成立**。两项在 PRD v1.14 即按产品决议补齐实现（`eventAt(x, y)` / `refreshNow()`），见 AD-28，落地证据见 §9.5。
2. **「声明了却无人消费」的专项审计**——AD-24 暴露的那类缺陷（`autoLocateOnFirstShow` 在 `attrs.xml` / `TimelineConfig` / `mergedWith` 里齐备，还有专项测试断言它被合并，唯独没有任何地方读它）在现有门禁下**完全隐形**：`verifyNoDeadCode` 只查私有成员，覆盖率门禁看的是「被测的逻辑」而非「被接到主流程上的逻辑」。建议的做法是把「每个可配置项至少有一个读取点」做成结构性断言（对照 `attrs.xml` 属性名 ↔ `ConfigFromAttrs` 读取处的字段名 ↔ View 层消费处），而不是再加百分比门禁。
3. **E29 与外部滚动模式的真机复核**——AD-04 的四条手势归属规则只经 `DayTimelineViewBehaviorTest.externalScrollModeDoesNotScrollItself` 单测覆盖，未在真机上验证过「外部容器滚动时组件不抢手势」。
4. ~~**M0 定稿——三项**~~ **已于 2026-10-15 全部完成**（v0.17 补齐追踪、v0.29 收口）：
   - ~~**M0-1 色值**~~ **已完成 2026-10-12**：已替换 `values/colors.xml` 与 `values-night/colors.xml` 各 17 项取值（浅深两套全部），AD-12 占位状态解除；
   - ~~**M0-2 交互标注**（状态流转图、手柄与热区标注）~~ **已交付 2026-10-15**：`docs/DayTimeline-交互标注与字体资源交付清单-设计回复.md`，经核对与现有实现一致、无代码改动；
   - ~~**M0-3 切图与字体资源**（字体规格、图标）~~ **已交付 2026-10-15**：字体规格（`sans-serif` / `sans-serif-medium`，副标题行高 16sp）落地 `Theme.kt`，新增仪器测试锁住字族映射（回滚验证会转红）；切图判定「无位图需求」；
   - ~~完成后重跑 **Q7 深色对比度走查（UF-004）** 与 **FC-002 换肤验收**~~ **已重跑**：Q7 随 M0-1 定稿色值走查（含 1 项登记偏差——浅色已过态 2.51:1），FC-002 随新色值同步重走。
5. ~~**pre-commit 钩子**~~ **已完成 2026-10-13**（`.githooks/pre-commit`，detekt → verifyNoDeadCode → verifyNoChildViews，本机指一次 `git config core.hooksPath .githooks` 即生效）。

   **依赖漏洞扫描：门禁已于 2026-10-17 在 CI 首次执行完成，并据此收窄了扫描范围**——`:verifyNoHighVulns`
   （OWASP Dependency-Check 13.0.0，CVSS ≥ 7.0）与 CI 的 `dependency-scan` job 均已就位。
   首次执行命中 70 个 CVE（51 个 ≥ 7.0），**全部落在构建期工具链**（AGP 的 `_internal-unified-test-platform-*`、
   Kotlin/detekt 的 `kotlinCompilerClasspath` 等），运行时依赖 0 命中；据此设
   `scanConfigurations = [debugRuntimeClasspath, releaseRuntimeClasspath]`。**收窄后的「0 个高危」仍待 CI 重跑确认**
   （本机到 NVD 出口限流，不能在本机复跑）。三个坑与本次新增的第 4 个坑见 §9.4.1。

   **文档完整性扫描仍然没有任何自动化手段**——无工具、无 CI 步骤，暂只能人工核对。
6. ~~`ACTION_CANCEL` 落入点击 / 长按分派~~ —— **已于 v0.12 修复**（AD-26），并补 3 条仪器测试锁住。
7. **「组件绿但业务方链路断」这一类缺陷的常规防线**——AD-26 顺带暴露：`app/` 此前**零自动化测试**，
   demo 里 `BottomSheetDialog.onDismiss` / `PopupWindow.onDismiss` 两条取消收尾只靠注释自证安全。
   本次已为 `:app` 建 `connectedDebugAndroidTest` 并接入 CI，但**只有取消链路**被覆盖；
   表单提交、删除二次确认、日期切换、配置面板等 demo 路径仍无自动化。
   根因与第 2 条同源：门禁只看「被测的逻辑」，不看「被接到真实界面上的逻辑」。
8. **§12.1 六项真机指标的人工采集流程**（AD-29 遗留）——首次显示耗时（中位数 / P95）、
   滚动掉帧率、拖拽跟手延迟、内存 1 小时平稳、停留 1 小时耗电，五项**确认无法自动化**，
   理由见 §9.2.2。遗留的不是「能不能测」而是「怎么测」：DL-09 需要一份**可复现的采集流程**
   （机型与系统版本、日程条数与排布、`gfxinfo` / `dumpsys meminfo` 的具体命令、
   采样次数、通过判据），否则「人工核对」会退化成「看一眼觉得没问题」——
   这正是 §12.4 里「文档完整性」那项至今只能靠人工的原因（漏洞扫描项已由第 5 条接手）。

### 9.4.1 依赖漏洞门禁的四个坑（AD-30，前三个 2026-10-13 本机实测，第 4 个 2026-10-17 CI 实测）

新增 `:verifyNoHighVulns` 时踩到的，**照旧文档抄必然再踩一次**：

| # | 坑 | 现象 | 处理 |
|:--:|---|---|---|
| 1 | **13.x 的 DSL 全换了** | `failOnSeverity` / `nvdApiKey` / `cacheDirectory` / `reportsDir` 全部 `Unresolved reference` | 改用 `failBuildOnCVSS.set(7.0f)` / `nvd.apiKey` / `data.directory`（是 `Property<String>`，**传 File 编译不过**）/ `outputDirectory`。**`failOnSeverity` 换成了 CVSS 分值**——PRD 的「高危」在 CVSS v3 里是 7.0–8.9，故阈值取 7.0（含严重级） |
| 2 | **Kotlin DSL 不生成访问器** | `dependencyCheck { }` 块整个不可用 | 必须 `configure<DependencyCheckExtension> { }` 显式指定类型 |
| 3 | **13.x 强制 NVD API 且强制要 key** | `dependency-check-core` 里 `NvdDatafeedSource` **已被移除**，只剩 `NvdApiDataSource`；无 key 时客户端仍把空串发出去，服务端回「Invalid API Key, length of 0 too short」，最终抛出 **430 行 Java 堆栈** | 加 `verifyNvdApiKeyPresent` 前置检查，缺 key 就给一句能照做的指引（实测降到 **40 行、2 秒**结束）。**且必须用 `mustRunAfter` 接线**——只靠 `dependsOn` 的声明顺序时，Gradle 实测仍先跑扫描，那句人话根本没机会出现 |
| 4 | **默认扫描范围把构建工具链也算进「发布物漏洞」**（2026-10-17 CI 首次跑通后才暴露） | 不设 `scanConfigurations` 时插件扫**所有可解析且非测试**的配置，于是 AGP 的 `_internal-unified-test-platform-*`（grpc / netty / protobuf）与 `kotlinCompilerClasspath` / `kotlinBuildToolsApiClasspath` / `kotlinKlibCommonizerClasspath` / `detekt`（`kotlin-compiler-embeddable`）一并纳入，报告 70 个 CVE 全是这些工具，任务直接红——而运行时依赖 0 命中 | 设 `scanConfigurations = [debugRuntimeClasspath, releaseRuntimeClasspath]`（OQ-2 的「运行时」口径）。**用白名单而非 `skipConfigurations` 黑名单**：新出现的工具链配置默认不扫。注意 `-test-` 两侧是连字符时匹配不上插件的 test 正则，`skipTestGroups` 挡不住它 |

#### 第四轮排查：key 本身是有效的，卡住的是本机到 NVD 的链路（2026-10-13）

配好 key 后连续三轮全部失败，排查结论如下（**与前三条不同，这次不是配置问题**）：

| 验证 | 方法 | 结果 |
|---|---|---|
| key 是否有效 | 分别用 query param 与 HTTP header 请求 NVD | **query param → 404；header → 200**。且**随机伪造的 key 走 header 同样 404**，据此判定是 key 有效、而 query param 这种传法不被接受 |
| 客户端用哪种传法 | `javap -c` 反编译 `open-vulnerability-clients` 9.0.6 的 `NvdCveClient` | 字节码为 `ldc "apiKey" → getfield apiKey → SimpleRequestBuilder.addHeader(...)`，**走 header**，与上面一致 |
| 那为什么还失败 | 退避后重跑，观察进程状态 | 运行 44 分钟后仍在 NVD 更新阶段，**20 秒内 CPU 仅增长 0.1 秒**（即空等网络），且缓存目录始终未创建 |

**结论**：配置、key、客户端三者均已确认无误；**瓶颈是本机（开发环境）到 NVD 的出口被严重限流**，
同样的调用用 .NET 的 HTTP 栈可以拿到 200，但 dependency-check 的 Apache HttpClient 5
在长时间运行中几乎拿不到有效响应。因此「首次全量同步」这一步**应在 CI（GitHub Actions）里完成**——
CI 的出口与本机不同、限流计数也相互独立，且 `.gradle/dependency-check-cache` 已在 CI 配置了缓存，
首跑之后每次都只做增量。

**本次遗留两条诚实的说明**：

1. `build.gradle.kts` 里把 `nvd.maxRetryCount` 调到 20、`nvd.delay` 保持 8 秒——
   这两个值**未经端到端验证**（运行始终没跑完），只是「更宽容」，已在代码注释里标明。
2. **「0 个高危漏洞」这个结论仍待 CI 确认**。2026-10-17 门禁首次在 CI 跑出报告——
   未收窄范围时命中 70 个 CVE 但**全在构建期工具链**，据此已把扫描收窄到运行时 classpath；
   收窄后的结果需 CI 重跑才能定论（本机到 NVD 出口限流，不能在本机复跑）。

**另有一个「门禁形同虚设」的坑**，与 §9.4 第 2 条同源，不实测发现不了：
插件若只装在根项目上，`:library` / `:app` / `:r8test` 根本没有 `dependencyCheckAnalyze` 任务；
而即便三处都装了，`dependencyCheckAggregate` **也不会**把它们挂进任务图，它只汇总**已生成**的报告。
两个问题叠加 = 「汇总一份空报告然后通过」。本机用 `--dry-run` 逐条查任务图才发现。

### 9.5 已完成（原列为待办，现已具备证据）

| 项 | 证据 |
|---|---|
| AD-22 表单输入落地 | `applyEdit` / `EditDraft` / `EditResult` 已实现；demo 真实弹 BottomSheet 表单，真机手动验证通过 |
| AD-23 日程详情 | `detailOf` / `enterEditMode` / `enterEditModeAndNotify` / `clearSelection` 已实现；demo 真实弹 PopupWindow 详情，真机手动验证通过 |
| AD-24 首次定位（FI-012） | 见 AD-24。`firstShowAutoLocatesToOneThirdViewport` 等 9 个真机用例全绿；此前 17 个存量用例因该功能生效而失败并已修夹具（见 AD-24「连带影响」） |
| R8 混淆消费端验证 | 见 §9.2 该行。`MinuteOfDay` 只保类名不保成员的缺口、D3 断言的假通过，均由该模块首跑时实测抓到 |
| detekt 与死代码自动阻断 | `:library:detekt` / `:app:detekt` 0 违规；`:library:verifyNoDeadCode` 专项门禁 0 处 |
| 可测逻辑覆盖率门禁 | `:library:verifyAllCoverage` **89.61%**（3348/3736），已接入 CI（口径见 §9.2.1 / PRD §12.4.1） |
| **R9 关闭**（M1-8） | 三条实测：`javap` 显示 `MinuteOfDay` 不引用任何运行时 API（value class 时代擦除为 `int`，普通类时代为普通实例方法）；`minSdk 23` 构建通过且无 desugaring 兼容层；当时 74 个仪器用例真机全绿（现为 `:library` 82 + `:app` 7，见 §9.2）。PRD v1.8 §18 的 R9 已整条重写为「已排除」 |
| **Java 调用方可实现 `TimelineEvent`**（OQ-7 方案 A，2026-10-10） | `MinuteOfDay` 改普通类后 `javap` 方法名干净（`getStart()` 返回 `MinuteOfDay`，无 `-ZruiD9E` 混淆后缀）；`:r8test` 新增 `JavaBusinessEvent.java` + `javaCallerCanImplementContractAfterMinification`（当时该模块 7/7 真机全绿，现已增至 8/8，见 §9.2）。**反向验证：把 `MinuteOfDay` 换回 git 原版 value class，Java 文件立刻编译失败并直指 `getEnd-ZruiD9E()`**——守护用例确认咬得住 |
| **R8 保留规则补齐 `MinuteOfDay` 成员** | 由上面的新用例实测抓到（改普通类前该规则只保类名，跨 R8 边界 `NoSuchMethodError: getMinuteOfDay()I`）；已改 `-keep class …MinuteOfDay { *; }` 后复跑转绿 |
| AD-26 `ACTION_CANCEL` 不再触发点击 / 长按 | 见 AD-26。3 条仪器测试；**回滚修复后实测 3 条同时转红**，确认断言真的咬得住 |
| 外部滚动模式下的取消路径 | `cancelBehavesIdenticallyInExternalScrollMode` / `tappingOutsideStillCancelsInExternalScrollMode` / `e20AutoCancelAlsoWorksInExternalScrollMode`（此前该模式下取消路径零覆盖，D6 无证据） |
| `:app` demo 的仪器测试 | `MainActivityCancelPathTest` 7 条，接入 CI。**实测把 `onDismiss` 里的 `cancelEdit()` 删掉后 2 条转红**，确认不是空跑 |
| demo 编辑态入口收敛到弹窗 | 删除 `activity_main.xml` 的 `edit_actions` 三个常驻按钮，「完成 / 取消 / 删除」改由表单与详情弹窗提供。**代价与验证见 §9.6** |
| AD-27 编辑态存续边界 | 见 AD-27。PRD §11.4.1 + E32 + 幂等约定三档断言；`clearEditState` 收编 `stopEdgeScroll`（回滚后 2 条转红）。**本条不改变任何现有行为**，是定规格 + 补测试 |
| **AD-29 §12.1 性能指标的可自动化边界** | 见 §9.2.2。新增 `:library:verifyNoChildViews`（源码扫描门禁，接入 CI 静态 job，无需设备）、`PerformanceBenchmarkTest` 4 条（纯 JVM）、`RedrawConvergenceTest` 3 条；并为此新建 `library/src/androidTest/AndroidManifest.xml` 与宿主 `TimelineTestHostActivity`。**四处回滚验证全部实测**：注入子 View → 源码门禁红并逐条指名；列分配由贪心首次适配换成两两比较 → **形状门禁红（2.06 → 3.66）而绝对耗时门禁仍绿**；`requestRefresh` 去掉签名比对 → 收敛用例红（1 → 2 帧）；定时器改 200ms 无条件重绘 → 静止用例红（2 秒内 1 → 11 帧） |

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
- 组件侧一行未动——组件本来就不内置按钮（PRD §7.7 的「可配置是否内置」**已于 v1.22 由产品决议取消**，见 §9.4 第 1 条）。

#### 门禁

detekt / lintDebug / test / verifyNoDeadCode(0) / verifyCoreCoverage(91.81%) /
verifyAllCoverage(88.60%) / verifyKeptSymbols 全绿；`:app:connectedDebugAndroidTest` 7/7 全绿。
