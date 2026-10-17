# DayTimeline

可嵌入任意 Android 应用的**单日 24 小时时间轴视图组件**。当前仓库已进入**实施后期**：组件与 demo 均已实现并有门禁守护，**`1.0.0-rc1` 已对外发布、M6 尚未收口**（详见下方里程碑状态）。

## 置顶：密钥与隐私数据一律不得进仓库

**任何 API key、令牌、密码、私钥、账号信息，都不得写进代码、注释、文档、`*.properties`、
`*.kts`、CI yml、提交信息与 PR 描述。** —— 无论它是你刚生成的，还是从对话、终端输出、
日志里看到的：看到了不等于可以抄进仓库。举例时一律用 `<your-key>` 占位符
（`docs/依赖漏洞扫描操作手册.md` 里 NVD key 出现的位置写的就是 `<your-key>`，照那个来）。

本仓库目前唯一的密钥是 **NVD API key**，它的合法存放位置只有两处，**都不落盘**：

| 场景 | 位置 | 怎么设 |
|---|---|---|
| 本机 | 环境变量 `NVD_API_KEY` | `$env:NVD_API_KEY = "<your-key>"`，仅当前会话有效。**不要**写进 `gradle.properties`、shell profile 或任何文件 |
| CI | 仓库 Settings → Secrets and variables → Actions，名字必须一字不差 `NVD_API_KEY` | 步骤见 `docs/依赖漏洞扫描操作手册.md` |

`build.gradle.kts` 只通过 `providers.environmentVariable("NVD_API_KEY")` 读它——
**不要"顺手"改成从文件读**，那等于给密钥开一条落盘的口子。三条附带要求：

- **别把 key 贴在命令行或聊天里**：命令会进 PowerShell 历史（`Ctrl+↑` 就能翻出来），
  也会留在会话记录与终端日志里。本机要设就用 `Read-Host -AsSecureString`，
  或者干脆只在 CI 配 secret、本机不跑这道门禁。
- **怀疑泄露就轮换，别只删文件**：key 一旦进过文档、提交历史或会话记录，删文件也抹不掉历史。
  去 https://nvd.nist.gov/developers/request-an-api-key 重新申请一个。
  （本项目已经发生过一次：该 key 出现在过聊天记录与命令行里，手册末尾有记录。）
- **提交前自查**：`git diff --cached` 里出现疑似 key 的字符串就停下来处理。
  现有的 pre-commit 钩子（detekt / 死代码 / 常驻元素）**不检查密钥**，别指望它拦。

> **动对外签名前先看这一条**：`1.0.0-rc1` 已于 2026-10-11 发布（tag `95d081c`，见 `CHANGELOG.md`）。
> 技术方案 AD-22 原文写着——**「库尚未发布、改动代价最小」的窗口已经关闭**，
> 此后改动 `EditController` / `TimelineListener` / `TimelineEvent` / `MinuteOfDay` / `EditDraft` / `EditResult`
> 等对外签名即构成**破坏性变更**，接入方会直接编译失败。别再按「反正还没发布」的旧前提动手。

## 仓库现状（先看这个，避免误判）

- 组件实现进度（以技术方案 §9.1 为准）：**M0 三项设计定稿已齐备**——M0-1 色值 2026-10-12 定稿（17 项语义色 × 深浅两套 = 34 个取值，替换了 Material 3 baseline 占位值；第二版由品牌紫改为**品牌蓝**，Q7 已按蓝色系重走查，含 1 项保留偏差——浅色已过态文字 2.51:1）；M0-3 字体规格 2026-10-15 落地 `Theme.kt`（`sans-serif` / `sans-serif-medium`，副标题行高 16sp），切图判定「无位图需求」；M0-2 交互标注见 `docs/DayTimeline-交互标注与字体资源交付清单-设计回复.md`，经核对与现有实现一致、**无代码改动**。**M1–M5 已完成**、**M6 部分**——**DL-01~DL-09 交付物齐备**（DL-03 快速上手已补、DL-09 质量报告已定稿 v1.0）。发 `rc` 而非 `1.0.0` 反映的是 §1.2 残留的发布阻断项（已由 8 项降至 **4 项**，不再含 M0-2/M0-3），完整遗留清单见 `docs/DayTimeline-质量报告.md` §1.2。
  - **PRD v1.22 是一次需求删项，不是实现缺漏**：「可配置是否内置完成 / 取消 / 删除按钮」整项取消，**组件恒不内置**这三个按钮、也不弹删除二次确认框，全部由业务方经第四层 `EditController` 接管（PRD §7.7）。验收标准相应取消、不计入任何门禁——**不要把「组件没提供」当成 bug 去补**。
  **改动色值前先看 `docs/DayTimeline-设计侧定稿交付.md`** —— 那里的值是设计终稿，
  就地调色等于绕开设计评审，正是 M0-1 当初会产生占位值的同一个问题。
  `library` 有完整实现，`app` 是可运行的 demo（`:app` 已依赖 `:library`），另有第三个模块 `:r8test`（混淆消费端验证）。
  `library/src/main/java/` 下的 `core/` 是**纯 Kotlin、零 `android.*` 依赖**的核心算法，
  改它必须同步补单测。
- Git 已初始化，**当前在 `dev` 分支开发**（`master` 与 `origin/master` 停在发布打 tag 的那一版），
  remote 为 `git@github.com:kevinvane/DayTimelineAndroid.git`。
- **本地 pre-commit 钩子需指一次路径**：`git config core.hooksPath .githooks`
  （Git 不会把 `.git/hooks/` 随克隆带下来）。跑 detekt → verifyNoDeadCode → verifyNoChildViews 三项，
  约 3–10 秒；`SKIP_HOOKS=1` 或 `--no-verify` 可跳过，**CI 始终会拦**。
- `docs/` 下共 10 份文档，分四类：**规格**（产品与需求文档、技术方案与实施计划、日程块表单输入方案、
  色值交付清单、设计侧定稿交付）、**接入方文档**（完整能力文档 DL-04、主题定制指南 DL-05、
  交互说明文档 DL-06）、**发布材料**（质量报告 DL-09，草案）、**运维**（依赖漏洞扫描操作手册）。
  另有两个文件在仓库根目录：`LICENSE`（DL-07）与 `CHANGELOG.md`（DL-08）。
  **DL-03「快速上手文档」尚未交付**——写新文档前先看这条，免得重复造一份。

## 需求与技术方案来源

- `docs/DayTimeline-产品与需求文档.md`（PRD，**v1.22**，约 1542 行，UTF-8 中文，状态：待评审）——**唯一需求来源，动手前先读它。**
- `docs/DayTimeline-技术方案与实施计划.md`（**v0.28 草案**，约 1416 行）——架构决策 AD-01~AD-29、M0–M6 任务拆解、测试与门禁落地、实施进度、开放问题。**PRD §1.2 把架构与实现方案排除在外，这两份要配套读。**
- `docs/DayTimeline-日程块表单输入方案.md`（v0.1 草案，**已实现**）——PRD v1.3 新增的表单输入能力设计（AD-22）。**方案已定稿并已实现**。

读取注意：三个文件都是 UTF-8 中文，**PowerShell 控制台会显示成乱码**——用 read 工具读，或先设 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`。

PRD 中最该记住的几条：

| 位置 | 内容 |
|---|---|
| §7.2 | **所有默认尺寸/字号/时长的唯一定义处**（每小时格高 56dp、轴宽 56dp、吸附 15 分钟、当前时间线刷新 30 秒…）。§19.2 只是速查副本，**两者冲突时以 §7.2 为准** |
| §5.2 | D1–D20 共 20 条「已在同类实现中被发现、本产品不得重现」的质量缺陷。**D3（点取消后绝对不得发出任何数据变更通知）标为最严重**，改交互前必读 |
| §7.9 | 重叠日程宽度分配 W1–W6 六条规则（总列数=最大同时重叠数、只向右扩展不向左、扩展后左边界不变…），是 FR-006 的判定依据 |
| §9.1 | 数据契约：时间用「几点几分」表达，且必须让「传错时间单位」在**编译期**就不可能发生（对应 D18/D19） |
| §10.2 | 定制分四层，**第二至四层必须通过提供定制入口实现，不允许业务方改或复制组件源码**（D7） |
| §12.4 | 质量门禁：核心逻辑覆盖率 ≥90%、全库 ≥75%、死代码 0 处 |

**PRD 刻意不含技术架构与实现方案**（§1.2 明确列为「不属于本文档」），对应的《技术方案文档》由其配套输出（当前 v0.28）——不要假装 PRD 已经指定了实现方式。

## 硬性约束

- **零第三方依赖**（§12.2 / K10）：只能用 Android 平台 + AndroidX 官方库。不要引入 Room、Glide/Coil、DI 框架等。
- **同时支持「组件自身滚动」与「外部容器滚动」两种模式**，任何一种下都不能崩（D6）；两种模式对应不同的高度约定，见 §8.6。
- **时间轴网格区常驻界面元素数必须为 0**（§12.1），且**严禁持续不收敛的重绘循环**（D17）——这是硬指标，隐含要求走自绘/复用而非常驻 View 树。
- 拖拽吸附必须**上下对称**（D11）；触摸热区 ≥48dp 而视觉可更小，编辑手柄为「视觉 6dp + 热区 48×48dp」（§7.2 / UF-001）。
- 脏数据一律「容错 + 事件上抛」，修正对用户不可见，**绝不崩溃**（§9.3 / §11.4）。
- 全部颜色走系统主题，深浅色都要可用（D9/D14），文案随系统语言变化（D13），须支持 RTL（D13 / §12.2）。

## 技术栈（均为已验证事实）

- Gradle 8.13（wrapper 已缓存）+ AGP 8.13.2 + Kotlin 2.0.21；本机 JDK 17；`jvmTarget`/source-target 均为 **11**。
- **View 体系，不是 Compose**——`gradle/libs.versions.toml` 里没有任何 Compose 依赖，UI 栈是 AppCompat + Material 3 + ConstraintLayout。
- `compileSdk` 用的 AGP 8.13 新 DSL：`compileSdk { version = release(36) }`，不是常见的 `compileSdk = 36`。`minSdk = 23`，`targetSdk = 36`。
- 包名：`com.github.kevinvane.daytimeline.app` / `com.github.kevinvane.daytimeline.library` / `com.github.kevinvane.daytimeline.r8test`。
- `settings.gradle.kts` 设了 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` → **任何模块的 build 文件里加 `repositories {}` 会直接失败**。
- 依赖版本统一加到 `gradle/libs.versions.toml`，不要在模块里写裸坐标。

## 环境注意事项

- `ANDROID_HOME` / `ANDROID_SDK_ROOT` **均未设置**；SDK 路径来自 `local.properties` 的 `sdk.dir=D:\Android\Sdk`。该文件被 gitignore，**新克隆的仓库里没有它**，缺失时构建会在配置阶段直接失败——先让每人本地建一份（或改用 `ANDROID_HOME` 环境变量）。
- `compileSdk 36` 需要本机装有 `android-36` 平台（已确认存在）。
- **`core-ktx` 被钉在 1.18.0 是有原因的**（`gradle/libs.versions.toml` 里写了注释）：1.19.x 起要求 `compileSdk 37` + AGP 9.1+，
  而那会连带 Gradle 9.x wrapper、Kotlin 版本，甚至重写 build 脚本 DSL——与既定技术栈全面冲突（技术方案 §2.2 有完整取舍表）。**别只改一行版本号。**

## 命令 ≠ 能运行（这条最容易踩）

**编译通过、lint 干净、单测全绿，三者都证明不了 App 能起来。**
2026-10-08 发生过一次真机崩溃：`dimens.xml` 里用 `<item format="float" type="dimen">`
声明无量纲比值，AAPT2 按 `format` 编译成 `TYPE_FLOAT`，而 `Resources.getDimension()`
只接受 `TYPE_DIMENSION`，启动即抛 `NotFoundException: type #0x4 is not valid`。
当时 lint 0 错误、107 个单测全绿、核心覆盖率 91.08% 全部达标——**唯独没在真机上跑过**。

因此：

- **JVM 单测加载不了 Android 资源**，「资源类型与读取方式是否匹配」这类缺陷对单测完全隐形。
  组件相关改动后至少要跑一次仪器测试：
  `.\gradlew.bat :library:connectedDebugAndroidTest`（需设备；`adb devices` 可查）
- **无量纲比值不要写进 `dimens.xml`**：用代码常量，或 `<attr format="float">` + `TypedArray.getFloat()`。
  详见 `dimens.xml` 内的警示注释与技术方案 AD-15。
- **`res.getDimension()` 返回的已是 px**，不要再套一层 `TypedValue.applyDimension`，
  否则 density 被乘两次（字号在 density=3 设备上放大三倍）。见 AD-16。
- **凡是用 `Paint` 绘制文字，都要显式 `textSize`**——默认值是 12 **原始像素**不是 12sp。
  曾因 `blockText` 漏设，真机上块内文字小到看不见。见 AD-19。
- **`OverScroller` 的 X/Y 轴参数填错不会报错**，但会让惯性静默失效（`currY` 恒为 0 → 滑一下弹回顶部）。
  `fling` / `startScroll` / `computeScroll` 必须统一用同一根轴。见 AD-21。
- **手势"判定"与"执行"必须一起实现**。曾出现 arbiter 正确判定出滚动意图，
  回调里却只 `return false`，拖拽滚动根本没写，结果只能看到一屏。见 AD-20。

## 常用命令

Windows 下用 `gradlew.bat`：

```powershell
.\gradlew.bat projects                      # 确认工程可配置（已验证可用）
.\gradlew.bat :library:assembleDebug        # 只编组件库
.\gradlew.bat :app:assembleDebug            # 只编 demo
.\gradlew.bat :app:installDebug             # 装到已连接设备/模拟器
.\gradlew.bat test                          # 全部 JVM 单元测试（不需要设备）
.\gradlew.bat :library:testDebugUnitTest --tests "com.github.kevinvane.daytimeline.library.*"
.\gradlew.bat :library:verifyCoreCoverage   # 核心逻辑覆盖率门禁 ≥90%（实测 93.28%）
.\gradlew.bat :library:verifyAllCoverage     # 全库可测逻辑覆盖率门禁 ≥75%（实测 89.58%，不需要设备）
.\gradlew.bat detekt                          # 静态检查 + 代码风格 0 违规
.\gradlew.bat :library:verifyNoDeadCode       # 死代码 0 处（依赖 detekt）
.\gradlew.bat :library:verifyNoChildViews     # 网格区常驻界面元素数 = 0（不需要设备，秒级）
.\gradlew.bat :library:verifyColorsFinalized  # 17 项语义色 × 深浅两套 = 34 个取值须等于设计定稿 + 对比度达标（依赖 detekt）
.\gradlew.bat verifyNoHighVulns               # 依赖漏洞扫描：运行时 classpath 上 CVSS ≥ 7.0 的漏洞 0 个（**本机跑不通，必须走 CI**）
.\gradlew.bat :r8test:verifyKeptSymbols       # 混淆产物构建 + 对外契约类符号核对（不需要设备）
.\gradlew.bat connectedAndroidTest           # 需要设备/模拟器（含 :app 的 11 条 demo 用例）
```

`lint` 用的是 AGP 默认规则 + `lint { warningsAsErrors = true }`——**没有配置任何 lint baseline**，
所以新增一条 warning 就会让构建失败。这是刻意的：门禁靠「不容忍」才有意义。

## 基础设施现状

已具备（PRD §12.4 / §12.5 要求的主要门禁均已落地并接入 CI）：

- **detekt**（`config/detekt/detekt.yml`）：静态检查 + 代码风格 0 违规。
- **死代码门禁** `verifyNoDeadCode`：扫描未被引用的私有成员，0 处即通过。
  与 detekt 分两步跑，失败时能一眼看出是哪条红线。
- **JaCoCo 覆盖率门禁**：`verifyCoreCoverage`（core ≥90%，实测 **93.28%**，2513/2694）与
  `verifyAllCoverage`（core + api 可测逻辑 ≥75%，实测 **89.58%**，3354/3744）。
  口径与敏感度边界见 `library/build.gradle.kts` 里 `verifyAllCoverage` 的 KDoc。
  **数据来源**：2026-10-16 本机实跑 `verifyCoreCoverage` / `verifyAllCoverage` 的输出。
  较 2026-10-13 那版的 93.34% / 89.61% 略降——因 `DayTimelineViewBehaviorTest`
  去掉了写死的像素几何、改从真实资源推导，测试代码本身不再计入分母中的可执行路径比例。
  **引用覆盖率时一律以本机实跑输出为准**，不要照抄 PRD 表或历史值（PRD §12.4 至今仍写着
  v1.13 期的 88.60%，那是 3310/3736 的旧分母）。
- **R8 混淆消费端验证**（`:r8test` 模块）：核对对外契约类是否按 `consumer-rules.pro`
  保留，再在**混淆后的 release 变体**上跑仪器测试——这是「业务方开启混淆后功能正常」
  的唯一有效证据（library 自身不开混淆，保留规则写错也不会有任何东西报错）。
- **CI**（`.github/workflows/ci.yml`）：三个 job——静态门禁 / 仪器测试 / R8 验证。
- **色值定稿门禁** `verifyColorsFinalized`：§16.2 的 M6 出口硬卡点原先只存在于文字里，
  占位值可再次混入发布物而无人拦截。新增后核对三件事——色名集合与设计交付完全一致
  （兼挡「漏配」与「改名」）、17 项 × 深浅两套 = 34 个取值须等于设计定稿、按 §7.3 算 WCAG 对比度。
  已回滚验证（改回 M3 占位 → 红；深色文件删色名 → 红并指名缺哪个）。
  **改色值会被这道门禁拦下，除非同步更新设计交付文档与 `build.gradle.kts` 的登记表**。
- **常驻界面元素门禁** `verifyNoChildViews`（2026-10-13 新增）：§12.1 的「网格区常驻界面元素数 = 0」
  原先验证方式只写着「代码走查」——人工走查挡不住下一次改动：谁为了修命中测试或做无障碍焦点
  而往时间轴里塞一个子 View，静态检查不报、单测全绿、真机也看不出，指标却已经破了。
  现扫 `library/src/main` 里 12 个创建/挂载标记（`addView(` / `ViewGroup(` / inflate 等）与全部布局资源，
  **对话框不拦**（独立 window、不常驻）。已回滚验证：注入含 `addView`/`TextView` 的子 View → 红并逐条指名；
  KDoc 里写着禁令原文的句子不误报（先剥注释）。运行时一侧由 `RedrawConvergenceTest` 复核。
- **性能基准（部分）** `PerformanceBenchmarkTest`（2026-10-13 新增，纯 JVM 无需设备）：
  布局 200 条 ≤ 4ms、增量更新 100 条 ≤ 16ms，外加一条**增长形状**门禁。
  **读这三个数字时必须知道的**：绝对耗时门禁的余量有 3.6–62 倍，实测把列分配从贪心首次适配
  换成两两比较后真实分布涨到 1.10ms 仍**不转红**；真正转红的是形状那条（2.06 → 3.66）。
  §12.1 九行指标里只有这 2 行 + 常驻元素数共 3 行能自动阻断，
  其余 6 行（首次显示中位数 / P95、滚动掉帧率、拖拽跟手、内存、耗电）**仍无自动化**，
  理由见 PRD §12.1 末尾——模拟器帧时序不代表真实设备。
- **依赖漏洞门禁** `verifyNoHighVulns`（2026-10-13 新增，OWASP Dependency-Check 13.0.0，阈值 **CVSS ≥ 7.0**）：
  **⚠️ 不要在本地跑这道门禁。** 本机到 NVD 的出口被严重限流，单次全量同步最长跑到
  **5 个小时仍未完成下载**（官方口径 30–45 分钟，差 6 倍以上）——不是「等久一点就好」，
  是**跑不通**；多次重试**累计放弃约 10 小时**。要跑**必须走 CI**（`dependency-scan` job：
  出口 IP 与本机不同、限流计数独立，且已配 `.gradle/dependency-check-cache` 缓存）。
  本地跑的唯一合理场景是「CI 挂了，想读某个具体报错」，
  此时也**不要**指望它跑通。详见 `docs/依赖漏洞扫描操作手册.md` 开头的警告块。
  **该门禁已于 2026-10-16 从发布阻断项降级为「可选增强」**（`docs/DayTimeline-质量报告.md` §1.2）。
  **2026-10-17 该门禁首次在 CI 执行完成**（此前「从未跑通」）——且**原因已经换过一轮，别再照旧的说**：
  ① 13.x 起只支持 NVD API 且强制要求 key（免费申请：
  https://nvd.nist.gov/developers/request-an-api-key ，前置检查 `verifyNvdApiKeyPresent` 会拦下并给指引）；
  ② **key 已配好且有效**（2026-10-14 第四轮排查，提交 `c131e93`：header 传法 → 200，伪造 key → 404；
  `javap -c` 反编译确认客户端也走 header），本机跑不通的瓶颈**纯粹是出口限流**——
  第四轮观测跑 44 分钟仍停在更新阶段、20 秒内 CPU 仅涨 0.1 秒。
  **44 分钟与 5 小时都是单次运行的实测观测，不是同一次的前后半段**；
  多次重试**累计放弃约 10 小时**。
  三个坑（13.x DSL 全换 / Kotlin DSL 不生成访问器 / 必须 `mustRunAfter` 接线）+ **本次新增第 4 个坑**
  （见下条）见技术方案 §9.4.1。
  **首次执行的结果不是 0 个高危**：命中 **70 个 CVE（51 个 CVSS ≥ 7.0）**，但**全部落在构建期工具链**——
  AGP 的 `_internal-unified-test-platform-*`（→ grpc / netty / protobuf）与 Kotlin/detekt 的
  `kotlinCompilerClasspath` 等（→ `kotlin-compiler-embeddable`），这些坐标不在 `gradle/libs.versions.toml`、
  也不进 AAR/APK；**运行时依赖 0 命中**。第 4 个坑即此：**默认扫描范围把构建工具链也算进来**，
  且 AGP 那批配置名里的 `-test-` 两侧是连字符、匹配不上插件 test 正则，默认 `skipTestGroups` 挡不住它。
  据此把扫描收窄为 `scanConfigurations = [debugRuntimeClasspath, releaseRuntimeClasspath]`（白名单，非黑名单）——
  本机已验证收窄后扫描集（`:app` 60 / `:library` 58 / `:r8test` 58 个文件）**0 个工具链命中**。
  **收窄后的「0 个高危」仍待 CI 重跑确认**——本机到 NVD 出口限流，无法在本机复跑。
  顺带关闭了 **OQ-2**：K10 落定为「**运行时**零非 AndroidX 第三方依赖；构建与测试期允许工具类依赖」。
- **仪器测试宿主窗口**（`library/src/androidTest/AndroidManifest.xml` + `TimelineTestHostActivity`，
  2026-10-13 新增）：此前 102 条仪器测试**全部**是「裸 Context + `measure`/`layout`」，
  组件从未 attach 到窗口——那种 View 上的 `invalidate()` 不调度任何绘制，
  `ViewTreeObserver.OnDrawListener` 一次都不会触发。**要做「有没有发生重绘」类断言，
  必须先挂进窗口**，这是原有基础设施下原理上做不到的事。
- **仪器测试** `:library` **115** 条 + `:app` **11** 条 + R8 混淆变体 **8** 条
  （2026-10-16 在 Pixel Fold / Pixel_XL_API_34 / API 23 平板真机上均实测全绿）。
  `:app` 的 11 条 = 7 条取消链路（`MainActivityCancelPathTest`）+ 4 条缺口避让
  （`MainActivityWindowInsetsTest`）。

> **测试套件也有隐含前提，且已踩过一次**（2026-10-16）：
> `DayTimelineViewBehaviorTest` 曾把几何写死成像素值（`HOUR_HEIGHT=200` / `HEIGHT=1920` /
> `VIEWPORT=800`），而产品尺寸取自 `dp` 资源、随设备密度缩放——等于隐含假设
> 「设备是 420dpi 高密度手机」。结果在 160dpi 真机上 3 条用例成批转红，
> **而产品行为完全正确**（`library/src/main` 零改动）。现已改为从真实资源推导几何，
> 320 / 560 / 160dpi 三档密度各 115 条全绿。
> **写测试时留意这一类前提**，否则换台设备就得到一片红，且极易被误判成产品缺陷。

尚未建立：

- **PRD §12.4 十项门禁的落地分布：六项自动阻断、两项部分落地、一项已在 CI 首次执行但收窄后待重跑确认、一项完全未落地。**
  部分落地的是「界面自动化冒烟」（`:app` 11 条：7 条取消链路 + 4 条缺口避让）与「性能基准」（§12.1 九行中 3 行自动阻断）。
  「已知安全漏洞 0 个高危」的门禁（`verifyNoHighVulns`）**2026-10-17 已在 CI 首次执行完成**——
  首次命中全在构建期工具链、运行时 0 命中，据此已把扫描收窄到运行时 classpath；
  **收窄后的「0 个高危」仍待 CI 重跑确认**（本机到 NVD 出口限流，**在本地跑不通**）。
  **该项已于 2026-10-16 降级为「可选增强」，不再是发布阻断项。**
  「文档完整性（无遗留待办标记）」**完全没有任何自动化手段**，只能人工核对。
  §12.1 余下 6 行（首次显示中位数 / P95、滚动掉帧率、拖拽跟手延迟、内存 1 小时平稳、1 小时耗电）
  同样只能人工真机走查，理由见 PRD §12.1 末尾；清单见 `docs/DayTimeline-质量报告.md` §4。
- **发布阻断项的权威清单是 `docs/DayTimeline-质量报告.md` §1.2，逐项标注依据与状态**，
  §4 另有「必须人工真机走查」清单。下发布结论或改门禁前先对一遍那份表——
  本节的概述讲的是**分布**，不是清单，不要拿它当核对依据。
  **2026-10-16 现状：阻断项已由 4 项降至 0 项**。四项的处置如下，
  **每条都写明了依据，没有一条被折算成「通过」**：
  ① 漏洞扫描 → **降级为可选增强**（本地跑不通，单次最长 5 小时未完成，必须走 CI；
     2026-10-17 已在 CI 首次执行完成，但首次命中全在构建期工具链，**收窄后的结论仍待 CI 重跑确认**）；
  ② P6 耗电 → **降级为可选**（**从未实测**，唯一真机电池损坏、采集条件在仓库可控范围外；
     耗电暴露面经代码走查确认极小——但**「暴露面小」不等于「实测达标」**）；
  ③ 设计对比度复核 → **已闭环**（A+C，取值 3.04:1，门槛定为 ≥3.0:1）；
  ④ 分屏 → **降级为可选**（resize 路径已验证；真多窗口的**会话管理三段未覆盖**，
     根因是可用的系统镜像均不声明 `android.software.multiwindow` feature，
     与产品缺陷无关）。

  > **「阻断项 0 项」不等于「全部验证通过」**——②④ 至今没有任何实测支撑；
  > ① 已在 CI 跑过一次（命中全在构建期工具链），但收窄后的「0 个高危」仍待重跑确认。
  > 准确说法是「所有剩余待办都不再阻塞发版」。接入方需自行验证的部分
  > （缺口避让、多窗口）已写入 `docs/DayTimeline-完整能力文档.md` §8.2 / §8.3。
- 无死代码检查工具之外的「删除前确认」类防护。
- **`:app` demo 只覆盖了取消链路、表单提交与删除二次确认的「放弃」分支**
  （`MainActivityCancelPathTest` 7 条）。删除成功、日期切换、配置面板等业务方侧路径
  仍无自动化——这类缺陷的特点是**组件测试全绿也照样发生**，
  因为收尾代码写在业务方而不在组件里。
- 仪器测试的执行数据**不并入** JaCoCo JVM 报告，因此覆盖率门禁看不到
  View / 绘制 / 资源读取层的执行情况。补上需要 `jacoco-android` 等第三方插件，
  与 K10「零第三方依赖」冲突，待单独评估。

## demo 的一处已知依赖

`MainActivity` 的 `EditController.onEnterEditing` 恒返回 `true`（接管必然生效，FI-010 随即失效），
而底部常驻的「完成 / 取消 / 删除」按钮已删除（见技术方案 §9.6）——
编辑态入口全部走表单与详情弹窗。

**按钮为什么没了**：不是 demo 的自主选择，而是 **PRD v1.22 把「可配置是否内置完成 / 取消 / 删除按钮」整项需求删掉**
——组件恒不内置这三个按钮，删除二次确认框也一并归业务方。demo 里剩下的只是 `EditController` 接管后
自己弹的表单与详情弹窗。动手改这块之前先读 PRD §7.7 的决议理由，别把它当成待补的实现缺口。

**后果**：唯一「有编辑态却没有任何对话框」的状态（详情 → 删除 → 二次确认按返回放弃，
PRD 场景 E 的 E4）**只能靠 FI-010「点组件外部区域视为取消」退出**。
改 FI-010 语义、或让 `editTakenOver` 在该路径上残留时，demo 会静默失去出口。
`MainActivityCancelPathTest.abandoningDeleteConfirmationCanStillEscapeByTappingOutside`
就是守住这条依赖的用例，动 FI-010 前先看它。

## 一条自省的教训

**我曾把「编译通过、lint 干净、单测全绿」当成「功能正常」汇报过一次，结果 App 在真机上直接崩。**
这三项都不加载或不过问 Android 运行时。凡是声称「没问题」的结论，
要么说清覆盖了什么，要么明确写「未在真机验证」。

## 第二条：写完就绿，不等于测试有效

**新写的测试第一次就通过时，要先确认它真的咬得住。**
2026-10-10 修 `ACTION_CANCEL` 与 demo 取消链路时，两处都用「回滚修复 → 重跑」验证过：

- 把 `onTouchEvent` 的 `ACTION_CANCEL` 修复还原 → 3 条测试同时转红，
  失败信息逐条对应缺陷症状（「凭空进入新建编辑态」/「凭空触发长按回调，实际回调：[click]」）。
- 把 `MainActivity.onDismiss` 里的 `cancelEdit()` 删掉 → 2 条测试同时转红。

不这么做的话，一批全是「只断言了注释里那句话」的测试也能全绿，
它们唯一的价值是让人误以为覆盖了。**新测试的第一次绿灯值得警惕，红过一次才算数。**

反过来，写断言时也常踩这个坑：先断言「数据变了」还是先断言「数据没变」，
决定了用例是有效还是空跑——`cancellingTheFormChangesNothing` 先在表单里改了标题再取消，
否则「什么都没丢」也能通过。**要让对照组先证明这条链路本来是能生效的。**

## 第三条：「看着像死代码」的东西，先跑一遍再说

评估 `activity_main.xml` 的 `edit_actions`（底部常驻的「完成/取消/删除」）时，
按代码推理的结论是「死 UI 可删」：`EditController.onEnterEditing` 恒接管，
四个进入编辑态的入口里三个立刻弹模态表单盖住按钮，第四个紧跟终态调用——
**没有任何状态能点到底部按钮**。原注释「未接管时用页面底部的按钮走传统路径」
还写着一个 demo 里永远走不到的分支，进一步佐证。

实测推翻了它：详情 → 删除 → 二次确认按返回放弃（场景 E 的 E4）时，
编辑态保留而三个窗口全关，底部「取消」是当时唯一显眼的出口。

**这类推理的盲区是「跨窗口的状态残留」**：对话框 A 打开时状态是 X，
关掉 A、弹 B 之前的那一瞬间，屏幕上什么都没有而状态仍停在 X。
纯读代码容易把这条路径当成「不可能到达」。**判断某个 UI 元素是否死代码，
先写条测试把状态驱动出来，比读一百行调用链可靠。**

（注：这三条记录的推理过程仍然成立，但**结论已被 PRD v1.22 覆盖**——
「内置按钮」这项需求已整项取消，按钮最终确实删除了，只是理由不再是「它是死 UI」。）

## 第四条：看起来合理的补丁，也可能什么都没做

给「编辑态 detach 后重验数据引用」补了一行 `revalidateEditingAgainst(events)`——
方向对、理由充分、KDoc 也写得挺好。**把它删掉，82 条测试仍然全绿。**

原因是 `events` 只可能由 `submitEvents` 改变，而那里已经做过 E20 校验，
reattach 时再验一次是对同一份数据的第二次比对。已回退，并把「不要在这里补重验」
的理由写进 KDoc 与测试注释。

**比第一课更值得警惕**：新测试全绿只证明「没有测到变化」，不证明「改动有用」。
判据是**回滚后测试是否变红**——那条对 `stopEdgeScroll` 有效（2 条转红），
对这一行无效（一条都没变）。**回滚验证顺带告诉你改动到底有没有被覆盖到。**

## 第六条：看起来像「走了完整链路」的测试，可能一次都没走

给 `SavedState` 补回归测试时写了 `saveHierarchyState` / `restoreHierarchyState`，
跑完全绿。**回滚验证时把 `writeToParcel` 和 `Parcel` 构造同时改坏，93 条依然全绿**——
一次都没红，测试根本没咬住。

原因是 **Android 在同一进程内传递 `SavedState` 走的是对象引用，不经过 Parcel 序列化**。
断言通过靠的是「拿到了同一个对象」，`Parcelable` 的读写逻辑压根没被执行。
这条测试证明的是「状态对象还在」，不是「状态对象能存能读」——而后者才是
进程被杀后恢复时真正走的路径，也是用户会遇到的状态丢失场景。

改成真 Parcel 往返（`marshall` / `unmarshall` / `CREATOR.createFromParcel`）后，
同一处破坏立刻 4 条转红。

**判据**：凡验证 `Parcelable`，不要用 `HierarchyState` 那对 API 代替序列化——
那是短路路径。**凡是「看起来覆盖了整条链路」的断言，单独确认一次它走的是哪条路**
——本例中两条路的差异只有一行 `Parcel`，但结论完全相反。

## 第五条：「声明了却没有任何代码」——这类缺陷会重复出现

2026-10-08 是第一次（AD-24 的 `dtAutoLocateOnFirstShow`）：`attrs.xml`、`TimelineConfig`、
`mergedWith` 三处齐备，还有专项测试断言它被合并，**唯独没有任何地方读它**。
「支持自动定位当前时间」是个只会回答「支持」的功能。

2026-10-10 是第二次，而且更难发现：PRD §11 白纸黑字列着
「获取指定位置的日程」（P0）与「刷新当前时间线」（P1），
**连 `attrs.xml` 的声明都没有**——`eventAt` / `refreshNow` 两个方法**当时**根本不存在
（PRD v1.14 已按产品决议补齐实现，见技术方案 AD-28；此处记的是当时的事实，不是现状）。
第一次至少有「配置项被合并」的测试守着，第二次除了一行 PRD 之外什么都没有。

**共同点：声明、文档、规格三者齐全，实现为零。** 这类缺陷对现有门禁完全隐形：
`verifyNoDeadCode` 只看私有成员，覆盖率门禁只看「被测的逻辑」，
两者都不看「被接到主流程上的逻辑」。已在技术方案 §9.4 第 2 条登记待建结构性断言
（对照 PRD 能力清单 ↔ 对外方法名 ↔ 消费点），**建好之前只能靠人工核对**。

新增对外 API 或配置项时自问一句：**它真的被谁读到了？**

## 第七条：发布成功 ≠ 产物正确

2026-10-11 首次发布时，**JitPack 返回 `ok`、CI 全绿，产物却是废的**。
tag `1.0.0-beat` 打在落后 `master` 28 个提交的位置，解包 AAR 逐项核对后：
`EditDraft` / `EditResult` / `EventDetail` 三个类不存在，
`eventAt` / `refreshNow` / `enterEditMode` / `enterEditModeAndNotify` / `clearSelection` 五个方法不存在，
`MinuteOfDay` 仍是 `@JvmInline value class`（Java 业务方**连接口都实现不了**），
随包 `proguard.txt` 同样是旧版——缺三个新类的 `-keep`、缺接口 `$DefaultImpls` 保留，
**开混淆的接入方会运行崩溃**。

**判据**：发布链路上的绿灯证明的是「构建与上传成功」，**不证明「产物内容正确」**。
发版后必须解包 AAR 逐项核对类、方法、`proguard.txt`——这一步已固化为流程（技术方案 v0.21），
不要靠「发布没报错」来判断。另记一条实测事实：**AAR 内不含 `LICENSE`**（AGP 与 JitPack 都不附带），
重新分发的接入方需自行附带。

这条与 `:r8test` 是同一类教训的两面：那面是「只有以业务方身份在混淆产物上跑才看得见」，
这面是「只有解包产物才看得见」——源码审查与 CI 绿灯在两种情况下都不会报错。
