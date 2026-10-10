# DayTimeline

可嵌入任意 Android 应用的**单日 24 小时时间轴视图组件**。当前仓库已进入**实施后期**：组件与 demo 均已实现并有门禁守护，**尚未完成 M6 发布**（详见下方里程碑状态）。

## 仓库现状（先看这个，避免误判）

- 组件实现进度（以技术方案 §9.1 为准，2026-10-10）：**M0 部分**（仅色值有 Material 3 baseline 占位兜底，交互标注与切图/字体资源无交付）、**M1–M4 已完成**、**M5 实现完成但验收待 M0**（Q7 深色对比度依赖定稿色值）、**M6 部分**（尚缺 DL-03~DL-06 四份交付文档）。
  `library` 有完整实现，`app` 是可运行的 demo（`:app` 已依赖 `:library`），另有第三个模块 `:r8test`（混淆消费端验证）。
  `library/src/main/java/` 下的 `core/` 是**纯 Kotlin、零 `android.*` 依赖**的核心算法，
  改它必须同步补单测。
- Git 已初始化，分支 `master`，remote 为 `git@github.com:kevinvane/DayTimelineAndroid.git`。
- `docs/` 下有三份文档：PRD、技术方案、表单输入方案。

## 需求与技术方案来源

- `docs/DayTimeline-产品与需求文档.md`（PRD，v1.14，约 1486 行，UTF-8 中文，状态：待评审）——**唯一需求来源，动手前先读它。**
- `docs/DayTimeline-技术方案与实施计划.md`（v0.19 草案）——架构决策 AD-01~AD-28、M0–M6 任务拆解、测试与门禁落地、实施进度、开放问题。**PRD §1.2 把架构与实现方案排除在外，这两份要配套读。**
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

**PRD 刻意不含技术架构与实现方案**（§1.2 明确列为「不属于本文档」），对应的《技术方案文档》由其配套输出（当前 v0.17）——不要假装 PRD 已经指定了实现方式。

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
.\gradlew.bat :library:verifyCoreCoverage   # 核心逻辑覆盖率门禁 ≥90%（实测 91.81%）
.\gradlew.bat :library:verifyAllCoverage     # 全库可测逻辑覆盖率门禁 ≥75%（实测 88.60%，不需要设备）
.\gradlew.bat detekt                          # 静态检查 + 代码风格 0 违规
.\gradlew.bat :library:verifyNoDeadCode       # 死代码 0 处（依赖 detekt）
.\gradlew.bat :r8test:verifyKeptSymbols       # 混淆产物构建 + 对外契约类符号核对（不需要设备）
.\gradlew.bat connectedAndroidTest           # 需要设备/模拟器（含 :app 的 7 条 demo 用例）
```

`lint` 用的是 AGP 默认规则 + `lint { warningsAsErrors = true }`——**没有配置任何 lint baseline**，
所以新增一条 warning 就会让构建失败。这是刻意的：门禁靠「不容忍」才有意义。

## 基础设施现状

已具备（PRD §12.4 / §12.5 要求的主要门禁均已落地并接入 CI）：

- **detekt**（`config/detekt/detekt.yml`）：静态检查 + 代码风格 0 违规。
- **死代码门禁** `verifyNoDeadCode`：扫描未被引用的私有成员，0 处即通过。
  与 detekt 分两步跑，失败时能一眼看出是哪条红线。
- **JaCoCo 覆盖率门禁**：`verifyCoreCoverage`（core ≥90%，实测 **91.81%**，2466/2686）与
  `verifyAllCoverage`（core + api 可测逻辑 ≥75%，实测 **88.60%**，3310/3736）。
  口径与敏感度边界见 `library/build.gradle.kts` 里 `verifyAllCoverage` 的 KDoc。
  **数据来源**：以上是本地 `library/build/reports/jacoco/.../jacocoTestDebugReport.xml`
  （2026-10-10 15:27，晚于全部源文件）的实测值，未重跑测试。
- **R8 混淆消费端验证**（`:r8test` 模块）：核对对外契约类是否按 `consumer-rules.pro`
  保留，再在**混淆后的 release 变体**上跑仪器测试——这是「业务方开启混淆后功能正常」
  的唯一有效证据（library 自身不开混淆，保留规则写错也不会有任何东西报错）。
- **CI**（`.github/workflows/ci.yml`）：三个 job——静态门禁 / 仪器测试 / R8 验证。
- **仪器测试** 102 个（`:library:connectedDebugAndroidTest`，2026-10-10 实测全绿；含 AD-28 的
  16 条 `DayTimelineViewEntryPointTest` + `DayTimelineViewSavedStateParcelTest`）+ 7 个
  （`:app:connectedDebugAndroidTest`），R8 混淆变体 8 个。

尚未建立：

- **PRD §12.4 十项门禁里有三项无任何自动化落地**：「已知安全漏洞 0 个高危」、
  「性能基准（§12.1 全部指标）」、「文档完整性（无遗留待办标记）」——
  三者既不在 CI 也不在任何 Gradle 任务里，目前只能靠人工核对。表格里其余七项均已自动阻断。
- 无 pre-commit 钩子（当前只在 CI 里阻断）。
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
**连 `attrs.xml` 的声明都没有**——`eventAt` / `refreshNow` 两个方法根本不存在。
第一次至少有「配置项被合并」的测试守着，第二次除了一行 PRD 之外什么都没有。

**共同点：声明、文档、规格三者齐全，实现为零。** 这类缺陷对现有门禁完全隐形：
`verifyNoDeadCode` 只看私有成员，覆盖率门禁只看「被测的逻辑」，
两者都不看「被接到主流程上的逻辑」。已在技术方案 §9.4 第 2 条登记待建结构性断言
（对照 PRD 能力清单 ↔ 对外方法名 ↔ 消费点），**建好之前只能靠人工核对**。

新增对外 API 或配置项时自问一句：**它真的被谁读到了？**
