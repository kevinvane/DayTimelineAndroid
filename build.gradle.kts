import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt) apply false
    // 依赖漏洞扫描（PRD §12.4「已知高危漏洞 0 个高危」）。只作用于构建期，
    // 不进入任何模块的运行时 classpath——见下方 KDoc。
    alias(libs.plugins.owasp.dependency.check)
}

/**
 * 依赖漏洞扫描的统一配置。
 *
 * 抽成 [Action] 是因为**必须对每个模块各配一次**（见下方 `subprojects`），
 * 配置写两遍必然漂移——而漂移出来的门禁比没有门禁更糟。
 */
val configureDependencyCheck: DependencyCheckExtension.() -> Unit = {
    // PRD §12.4 的原话是「0 个高危」。CVSS v3 的分级：高危 = 7.0–8.9、严重 = 9.0–10.0，
    // 因此阈值取 7.0 即「高危及以上」。MEDIUM/LOW 记录但不阻断——
    // 否则哪天 Google 某个传递依赖报中危，整个项目会被一个自己无法修复的
    // 第三方问题卡住发布。
    failBuildOnCVSS.set(7.0f)

    // NVD API key：有则用（限速从 5/30s 放宽到 50/30s，首次同步快一个数量级）
    val nvdKey = providers.environmentVariable("NVD_API_KEY").orNull
    if (!nvdKey.isNullOrBlank()) {
        nvd.apiKey.set(nvdKey)
    }

    // 漏洞库缓存放在仓库内以便 CI 缓存（~/.gradle 已有缓存但不覆盖这个自定义目录）
    // 注意：13.x 的 `data.directory` 是 `Property<String>`，传 File 会编译不过
    data.directory.set(rootProject.file(".gradle/dependency-check-cache").absolutePath)

    outputDirectory.set(rootProject.file("build/reports/dependency-check/$name"))
    formats.set(listOf("HTML", "JSON"))
    showSummary.set(true)
}

// 根项目本身没有可分析的依赖配置，但**插件必须在根项目上应用**，
// `dependencyCheckAggregate` 才会被创建出来。
configure<DependencyCheckExtension>(configureDependencyCheck)

// 三个模块各装一次插件：插件只分析**自己所在项目**的依赖配置，
// 只装在根项目上时 `:library` / `:app` / `:r8test` 的依赖一个都进不了扫描范围——
// 实测 `gradlew dependencyCheckAggregate --dry-run` 里没有任何
// `:library:dependencyCheckAnalyze`，即「扫了个空集、门禁形同虚设」。
// 这不是假想，是本机 dry-run 实测到的结果。
subprojects {
    apply(plugin = "org.owasp.dependencycheck")
configure<DependencyCheckExtension>(configureDependencyCheck)
}

if (providers.environmentVariable("NVD_API_KEY").orNull.isNullOrBlank()) {
    logger.lifecycle(
        "[漏洞门禁] 未配置 NVD_API_KEY，扫描会先被前置检查拦下。" +
            "免费申请：https://nvd.nist.gov/developers/request-an-api-key",
    )
}

/**
 * 先检查 NVD key 再说人话。
 *
 * ## 为什么需要这一层
 *
 * dependency-check **13.x 强制走 NVD API**：`dependency-check-core` 里
 * `NvdDatafeedSource` 已被移除，只剩 `NvdApiDataSource`；而 NVD API
 * **要求提供 key**，无 key 时客户端仍会把空串发过去，服务端返回
 * 「Invalid API Key, length of 0 too short」，最终抛出一屏 Java 堆栈
 * （实测 430 行）。
 *
 * 一道门禁如果以这种形态失败，实际效果是**训练团队忽略它**——
 * 与 PRD §12.4「门禁靠不容忍才有意义」正好相反。
 * 因此在跑扫描**之前**先检查，缺 key 就给一句能照做的指引（实测降到 40 行、2 秒结束）。
 *
 * **必须用 `mustRunAfter` 而不是靠声明顺序**：Gradle 对同一批依赖任务的执行顺序
 * 并不保证按声明次序。实测只靠声明顺序时，扫描仍先跑并抛出一屏堆栈，
 * 那句人话根本没机会出现——这正是加这一层的意义所在，被架空就等于没加。
 */
val requireNvdApiKey = tasks.register("verifyNvdApiKeyPresent") {
    group = "verification"
    description = "依赖漏洞扫描的前置检查：NVD_API_KEY 必须已配置"
    doLast {
        if (providers.environmentVariable("NVD_API_KEY").orNull.isNullOrBlank()) {
            throw GradleException(
                """
                |依赖漏洞扫描需要 NVD API key，而当前环境变量 NVD_API_KEY 为空。
                |
                |dependency-check 13.x 起只支持 NVD API（数据源下载方式已移除），
                |而 NVD API 强制要求 key，无 key 时只能看到一屏 Java 堆栈。
                |
                |获取方式（免费，约 1 分钟）：
                |  1. 打开 https://nvd.nist.gov/developers/request-an-api-key
                |  2. 填写邮箱，收到 key 后保存为环境变量 NVD_API_KEY
                |
                |PowerShell（当前会话）：
                |  ${'$'}env:NVD_API_KEY = "你的 key"
                |CI：仓库 Settings → Secrets and variables → Actions 新增 NVD_API_KEY secret
                |
                |不配置的后果：CI 的 dependency-scan job 每次都红。
                |**不要**为了「让它变绿」而删掉这个检查或降低阈值。
                """.trimMargin(),
            )
        }
    }
}

// 让三个模块的扫描任务都在 key 检查之后跑
subprojects {
    tasks.matching { it.name == "dependencyCheckAnalyze" }.configureEach {
        mustRunAfter(requireNvdApiKey)
    }
}

/**
 * 三个模块各自的扫描任务路径。
 *
 * **必须显式接线**：`dependencyCheckAggregate` 实测**不会**把各模块的
 * `dependencyCheckAnalyze` 挂进任务图（`--dry-run` 里只有 aggregate 自己），
 * 它只负责汇总已经生成的报告。也就是说，只 `dependsOn("dependencyCheckAggregate")`
 * 的门禁会**汇总一份空报告然后通过**——正是「门禁形同虚设」的另一种形态。
 */
val perProjectScans: List<String> =
    subprojects.map { "${it.path}:dependencyCheckAnalyze" }

tasks.named("dependencyCheckAggregate") {
    mustRunAfter(requireNvdApiKey)
    mustRunAfter(perProjectScans)
}

/**
 * 统一命名成 `verify*`，与 `verifyNoDeadCode` / `verifyNoChildViews` 并列。
 *
 * 单独建任务而不是直接让人跑 `dependencyCheckAggregate`，有两个理由：
 * ① 门禁清单上有一行可以指名执行，失败时能一眼看出是哪条红线；
 * ② 将来若要加别的阈值，改这一处即可，不必去改 CI 脚本与每个人的本地记忆。
 */
tasks.register("verifyNoHighVulns") {
    group = "verification"
    description = "依赖漏洞门禁：已知高危漏洞 0 个（PRD §12.4），首次运行需下载 NVD 漏洞库"
    dependsOn(requireNvdApiKey)
    dependsOn(perProjectScans)
    // Aggregate 而非 Analyze：它把三个模块的报告汇总成一份
    dependsOn("dependencyCheckAggregate")
    doLast {
        logger.lifecycle(
            "[漏洞门禁] verifyNoHighVulns 通过：未发现 CVSS ≥ 7.0 的漏洞。" +
                "报告见 build/reports/dependency-check/",
        )
    }
}