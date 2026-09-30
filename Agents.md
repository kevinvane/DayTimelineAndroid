# DayTimeline

可嵌入任意 Android 应用的**单日 24 小时时间轴视图组件**。当前仓库处于**工程骨架阶段，尚未开始实现**。

## 仓库现状（先看这个，避免误判）

- `library/src/main/java/` **不存在**——组件本体一行代码都还没有，只有 Android Studio 模板生成的 `Example*Test`。
- `app` 只有模板 `MainActivity`（edge-to-edge + window insets），**且 `app/build.gradle.kts` 并不依赖 `project(":library")`**。任何 demo/示例工作都必须先自己补上这条依赖。
- Git 已初始化：分支 **`master`**（不是 `main`），目前只有一个 `init` 提交，**且未配置任何 remote**（`git push` 无处可推；要发布需先 `git remote add`）。
- `docs/` 下只有 PRD 一个文件，无其他配套文档。

## 唯一需求来源

`docs/DayTimeline-产品与需求文档.md`（v1.1，1225 行，UTF-8 中文，状态：待评审）。**动手前先读它。**

读取注意：文件是 UTF-8 中文，**PowerShell 控制台会显示成乱码**——用 read 工具读，或先设 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`。

PRD 中最该记住的几条：

| 位置 | 内容 |
|---|---|
| §7.2 | **所有默认尺寸/字号/时长的唯一定义处**（每小时格高 56dp、轴宽 56dp、吸附 15 分钟、当前时间线刷新 30 秒…）。§19.2 只是速查副本，**两者冲突时以 §7.2 为准** |
| §5.2 | D1–D20 共 20 条「已在同类实现中被发现、本产品不得重现」的质量缺陷。**D3（点取消后绝对不得发出任何数据变更通知）标为最严重**，改交互前必读 |
| §7.9 | 重叠日程宽度分配 W1–W6 六条规则（总列数=最大同时重叠数、只向右扩展不向左、扩展后左边界不变…），是 FR-006 的判定依据 |
| §9.1 | 数据契约：时间用「几点几分」表达，且必须让「传错时间单位」在**编译期**就不可能发生（对应 D18/D19） |
| §10.2 | 定制分四层，**第二至四层必须通过提供定制入口实现，不允许业务方改或复制组件源码**（D7） |
| §12.4 | 质量门禁：核心逻辑覆盖率 ≥90%、全库 ≥75%、死代码 0 处 |

**PRD 刻意不含技术架构与实现方案**（§1.2 明确列为「不属于本文档」），对应的《技术方案文档》尚不存在——架构决策由研发另行输出，不要假装 PRD 已经指定了实现方式。

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
- 包名：`com.github.kevinvane.daytimeline.app` / `com.github.kevinvane.daytimeline.library`。
- `settings.gradle.kts` 设了 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` → **任何模块的 build 文件里加 `repositories {}` 会直接失败**。
- 依赖版本统一加到 `gradle/libs.versions.toml`，不要在模块里写裸坐标。

## 环境注意事项

- `ANDROID_HOME` / `ANDROID_SDK_ROOT` **均未设置**；SDK 路径来自 `local.properties` 的 `sdk.dir=D:\Android\Sdk`。该文件被 gitignore，**新克隆的仓库里没有它**，缺失时构建会在配置阶段直接失败——先让每人本地建一份（或改用 `ANDROID_HOME` 环境变量）。
- `compileSdk 36` 需要本机装有 `android-36` 平台（已确认存在）。

## 常用命令

Windows 下用 `gradlew.bat`：

```powershell
.\gradlew.bat projects                      # 确认工程可配置（已验证可用）
.\gradlew.bat :library:assembleDebug        # 只编组件库
.\gradlew.bat :app:assembleDebug            # 只编 demo
.\gradlew.bat :app:installDebug             # 装到已连接设备/模拟器
.\gradlew.bat test                          # 全部 JVM 单元测试（不需要设备）
.\gradlew.bat :library:testDebugUnitTest --tests "com.github.kevinvane.daytimeline.library.*"
.\gradlew.bat connectedAndroidTest           # 需要设备/模拟器，当前仅有模板测试
```

`.\gradlew.bat lint` 可用，但**用的是 AGP 默认规则**——仓库没有配置任何 lint baseline。

## 尚未建立、但 PRD 要求的基础设施

这些**都不存在**，需要时得自己搭，且 PRD 门禁要求它们最终落地：

- 无 CI（没有 `.github/`）、无 pre-commit 钩子。
- 无 ktlint / detekt / spotless；`kotlin.code.style=official` 是唯一风格约定。
- 无测试覆盖率工具配置（§12.4 却要求 ≥90% / ≥75%）。
- 无死代码检查工具（§12.4 要求 0 处并「纳入自动化阻断」）。
- 无 R8/混淆验证（§12.5 要求组件在业务方开启混淆后功能正常）。
