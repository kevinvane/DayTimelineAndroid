# DayTimeline 开发指南

> **面向维护者与贡献者。** 只想把自己的项目接进来，请看
> [README](../README.md) 与[快速上手](DayTimeline-快速上手.md)——本文档与它们无关。
>
> 硬性约束与已踩过的坑集中在 [AGENTS.md](../AGENTS.md)，动代码前请先读那份。

## 目录

- [环境与构建](#环境与构建)
- [质量门禁](#质量门禁)
- [工程结构](#工程结构)
- [交互规则要点](#交互规则要点)
- [发布流程与产物核对](#发布流程与产物核对)
- [版本演进](#版本演进)
- [参与贡献](#参与贡献)

---

## 环境与构建

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
.\gradlew.bat detekt                         # 静态检查 + 代码风格 0 违规
.\gradlew.bat :library:verifyNoDeadCode      # 死代码 0 处
.\gradlew.bat :library:verifyNoChildViews    # 网格区常驻元素数 = 0
.\gradlew.bat :library:verifyColorsFinalized # 17 项色值须为设计定稿值 + 对比度达标
.\gradlew.bat :library:verifyCoreCoverage    # 核心逻辑覆盖率 ≥ 90%
.\gradlew.bat :library:verifyAllCoverage     # 可测逻辑覆盖率 ≥ 75%
.\gradlew.bat :library:lintDebug             # 平台规范检查 0 错误 0 警告

# 需要设备 / 模拟器
.\gradlew.bat :library:connectedDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :r8test:verifyKeptSymbols          # 混淆产物 + 对外契约类符号核对
.\gradlew.bat :r8test:connectedReleaseAndroidTest

# 依赖漏洞扫描 —— ⚠️ 不要在本地跑，见下
.\gradlew.bat verifyNoHighVulns
```

> ⚠️ **JitPack 的构建绿灯不是门禁。** 它执行的是
> `clean publishToMavenLocal -xtest -xlint`——**跳过测试与 lint，也不触发上面任何一个
> `verify*` 任务**。上面这张清单只由 [CI](.github/workflows/ci.yml) 把关。
> 发版前请自己跑一遍，或看 CI 结果。
>
> 另：仓库未应用 `maven-publish`、也没有 `jitpack.yml`，JitPack 会自行识别 Android library
> 模块并注入 `maven-publish`、自动选用 JDK 17（**消费者不受影响**），代价是本地
> 跑不了 `publishToMavenLocal`。

### 依赖漏洞扫描：不要在本地跑

```powershell
.\gradlew.bat verifyNoHighVulns    # ⚠️ 本机跑不通，必须去 CI
```

本机到 NVD 的出口被严重限流，首次全量漏洞库同步**实测跑了 5 个小时仍未完成下载**
（官方口径 30–45 分钟，差 6 倍以上）——不是「等久一点就好」，是**跑不通**。
多次重试累计放弃约 10 小时。

- **要跑就去 CI**：GitHub Actions 的出口 IP 与本机不同，限流计数相互独立。
- 本地跑的唯一合理场景是「CI 挂了，想读某个具体错误信息」，此时也**不要**指望它跑通。

该门禁已于 2026-10-16 从发布阻断项**降级为可选增强**。完整排查过程见
[依赖漏洞扫描操作手册](依赖漏洞扫描操作手册.md)。

### 环境

- JDK 17，Gradle 8.13，AGP 8.13.2，Kotlin 2.0.21
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` **任选其一**设置；否则需在仓库根目录建
  `local.properties` 并写入 `sdk.dir=D:\Android\Sdk`（该文件被 gitignore）
- `compileSdk 36` 需要本机装有 `android-36` 平台
- 本地 pre-commit 钩子需指一次路径：`git config core.hooksPath .githooks`
  （Git 不会把 `.git/hooks/` 随克隆带下来）。跑 detekt → verifyNoDeadCode →
  verifyNoChildViews 三项，约 3–10 秒；`SKIP_HOOKS=1` 或 `--no-verify` 可跳过，**CI 始终会拦**

---

## 质量门禁

| 门禁 | 状态 | 说明 |
|---|---|---|
| 核心逻辑覆盖率 ≥ 90% | ✅ 达标 | **93.28%**（2513/2694） |
| 可测逻辑覆盖率 ≥ 75% | ✅ 达标 | **89.58%**（3354/3744） |
| 平台规范检查 0 错误 0 警告 | ✅ 达标 | `warningsAsErrors = true`，无 lint baseline |
| 代码风格 / 静态检查 | ✅ 达标 | detekt 0 违规 |
| 死代码 0 处 | ✅ 达标 | `:library:verifyNoDeadCode` 自动阻断 |
| 网格区常驻元素数 = 0 | ✅ 达标 | `:library:verifyNoChildViews` 自动阻断 |
| 色值为设计定稿值 + 对比度达标 | ✅ 达标 | `:library:verifyColorsFinalized`：34 个取值须等于设计交付；§7.3 的「低对比度」下限 ≥ 3.0:1，**0 项登记偏差** |
| JVM 单元测试 | ✅ 达标 | **152** 个用例全绿 |
| 仪器测试 | ✅ 达标 | `:library` **115**、`:app` **11**、`:r8test` 混淆变体 **8** |
| R8 混淆消费端验证 | ✅ 达标 | 10 个对外契约类符号核对 + 混淆变体仪器测试 8/8 |
| 性能基准 | ⚠️ 部分落地 | §12.1 九行指标中 **3 行已自动阻断**（布局耗时、增量更新耗时、常驻元素数）+ D17 刷新收敛；其余 6 行只能真机走查 |
| 已知安全漏洞 | ⚠️ **门禁已建，未跑通** | `verifyNoHighVulns`，**须在 CI 跑** |
| 文档完整性（无遗留待办） | ❌ 未自动化 | 无工具或 CI 步骤可扫，只能人工核对 |

> 覆盖率数字为**本机实跑输出**（2026-10-16），不沿用历史值；引用时以实跑为准。

### 三条覆盖率 / 测试相关的教训

**覆盖率口径**：`paint` / `internal` / 根包（含 View 绘制与无障碍）在结构上拿不到
JVM 单测覆盖率，共 6519 条指令（约占总量的 59%），由 115 条仪器测试与真机走查覆盖。
因此 `verifyAllCoverage` 的统计范围是「**JVM 可测逻辑**」，不是全部代码。

**编译通过 ≠ 能运行。** 曾出现过 lint 干净、107 个单测全绿、覆盖率达标，
但真机启动即崩：`dimens.xml` 里用 `<item format="float" type="dimen">` 声明
无量纲比值，AAPT2 按 `format` 编译成 `TYPE_FLOAT`，而 `Resources.getDimension()`
只接受 `TYPE_DIMENSION`。此类问题对 JVM 单测与 lint 完全不可见，
因此仓库有专门的 `ResourceContractTest` 仪器测试守住资源契约。

**测试环境本身也有隐含前提。** 曾发生过：测试类把几何写死成像素值，
而产品尺寸取自 `dp` 资源、随设备密度缩放——等于隐含假设「设备是 420dpi 高密度手机」。
结果在 160dpi 真机上 3 条用例成批转红，**而产品行为完全正确**（`library/src/main` 零改动）。
现已改为从真实资源推导几何，320 / 560 / 160dpi 三档密度各 115 条全绿。
**写测试时留意这一类前提**，否则换台设备就得到一片红，且极易被误判成产品缺陷。

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
r8test/                   # 混淆消费端验证：以业务方身份开 R8 跑仪器测试
```

`core/` **禁止 import 任何 `android.*`**。这条纪律是覆盖率能达标的前提——纯 Kotlin 类可用
JVM 单测直接跑，不需要设备也不需要 Robolectric。

---

## 交互规则要点

完整的状态流转、手势判定与编辑态规则见[交互说明文档](DayTimeline-交互说明文档.md)。
最容易踩的三条：

- **取消绝不产生数据变更**（D3）——包括显式取消、点外部、数据回传后自动取消这三条路径
- **接管编辑层后，组件不再响应「点外部 = 取消」**，三个出口都由接入方负责
- **手势被系统中断时不按意图分派**（ACTION_CANCEL），不会凭空触发点击或进入编辑态

---

## 发布流程与产物核对

### 步骤

1. 确认工作区干净、在 `master` 上，且 `master` 已与开发分支合并并推送
2. 跑完[质量门禁](#质量门禁)全表
3. 更新 `CHANGELOG.md`（把 `[未发布]` 的内容归入新的版本节）
4. 补齐质量报告的机型验证清单与文档中的遗留「待补」标记
   —— PRD §12.4 有「无遗留待办标记进入正式版本」这一条，**且它没有任何自动化，只能人工核对**
5. 更新 README 的坐标与版本表
6. 打**附注 tag**（`1.0.0-rc1` 就是附注 tag，`git cat-file -t` 可验证）并推送
7. **解包 AAR 逐项核对**（下一节）
8. 把核对结果补写回 README「产物核对」与质量报告 §7

### 产物核对清单

**发布成功 ≠ 产物正确。** 上一版 `1.0.0-beat` 就是反例：JitPack 返回 `ok`、CI 全绿，
解包 AAR 后却发现缺 3 个类、5 个方法，`MinuteOfDay` 还带着 value class 标志
（Java 业务方连接口都实现不了）。

逐项核对：

- [ ] `EditDraft` / `EditResult` / `EventDetail` / `EventDetailKt` / `EditDraftKt` 五个类均在
- [ ] `detailOf` / `eventAt` / `enterEditMode` / `enterEditModeAndNotify` / `clearSelection` /
      `refreshNow` 六个方法均在
- [ ] `MinuteOfDay` 已无 `boxed-impl`（value class 标志），确为普通类，**Java 接入方可用**
- [ ] `proguard.txt` 的保留规则数与仓库 `consumer-rules.pro` 一致

> 没有任何自动化能发现「打 tag 的提交不是最新提交」——上一版发布物就曾因此陈旧
> （tag 打在落后 master 28 个提交的位置）。**tag 必须打在已推送的最新提交上。**

> 另记一条实测事实：**AAR 内不含 `LICENSE`**（AGP 与 JitPack 都不附带），
> 重新分发的接入方需自行附带。

---

## 版本演进

| 版本 | 日期 | 说明 |
|---|---|---|
| `1.0.0` | 2026-10-16 | 首个正式版。相对 rc1 的全部变化见 [`CHANGELOG.md`](../CHANGELOG.md) |
| `1.0.0-rc1` | 2026-10-11 | 发布时 4 项发布阻断项尚未处置，`rc` 如实反映当时状态 |
| `1.0.0-beat` | — | 已移除的废弃版本，曾因 tag 打在陈旧提交上导致产物缺类缺方法 |

### 为什么当初先发 `rc1`

`DL-09` 质量报告 2026-10-14 定稿，但**发布阻断项直到 2026-10-16 才清零**——
四项分别「闭环」或「降级为可选增强」：

| 原阻断项 | 处置 |
|---|---|
| 设计对比度复核 | ✅ 已闭环（A+C，取值 3.04:1，门槛定为 ≥ 3.0:1） |
| 依赖漏洞扫描 | 降级为可选增强（从未跑通） |
| 1 小时耗电 | 降级为可选（从未实测） |
| 真多窗口（分屏） | 降级为可选（resize 已验证，多任务会话未覆盖） |

**「阻断项 0 项」不等于「全部验证通过」** —— 三项至今没有任何实测支撑。
完整依据见[质量报告](DayTimeline-质量报告.md) §1.2。

---

## 参与贡献

欢迎 Issue 与 PR。

**提 Issue 前请先读** [产品需求文档](DayTimeline-产品与需求文档.md)：
组件的交互与数据契约几乎每一处都有对应的需求编号（如 `FI-004`、`D3`、`D11`），
带上这些编号能让问题定位快得多。

**行为变更、配置项增减、公开 API 改动，请先开 Issue 讨论**——
这些会影响所有接入方。`1.0.0` 已发布，此类改动即构成**破坏性变更**。

开发者请先读 [AGENTS.md](../AGENTS.md)，里面记录了本仓库的硬性约束与已踩过的坑。