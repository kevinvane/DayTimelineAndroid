import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory
import java.math.RoundingMode

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
    jacoco
}

detekt {
    // §12.4：静态检查 0 严重 + 代码风格 0 违规 + 死代码 0 处，三者统一由 detekt 兜。
    // buildUponDefaultConfig = true 表示在 detekt 默认规则集之上做收紧，
    // 而不是用一份我们自己维护的规则**子集**——子集会漏，且没人能发现漏了。
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("$projectDir/config/detekt/baseline.xml")
    // 刻意**不开** ignoreFailures：PRD T5 要求死代码「纳入自动化阻断」，
    // 开了这个开关就等于把检查变成装饰。
    ignoreFailures = false

    source.setFrom(
        files(
            fileTree("src/main/java") { include("**/*.kt") },
            fileTree("src/test/java") { include("**/*.kt") },
            fileTree("src/androidTest/java") { include("**/*.kt") },
        ),
    )
}

/**
 * 死代码门禁（PRD §12.4 / T5 / D20）。
 *
 * 单独建任务而不是只依赖 `detekt`，是为了让门禁在 CI 与本地都能被**指名**执行，
 * 且能与覆盖率门禁并列出现在门禁清单里。
 *
 * 与 detekt 主任务的关系：本任务只在 `maxIssues = 0` 之上再确认一次
 * 「没有任何 UnusedPrivate* 命中」——把 T5 这条最硬的要求单独显式化，
 * 避免将来有人调 detekt 配置时顺带把死代码检查放松掉。
 */
tasks.register("verifyNoDeadCode") {
    group = "verification"
    description = "死代码门禁：不允许任何 UnusedPrivate* 命中（PRD T5 / D20）"
    dependsOn("detekt")
    val reportDir = layout.buildDirectory.dir("reports/detekt")
    inputs.dir(reportDir)
    outputs.upToDateWhen { false } // 每次都真跑，避免增量跳过导致门禁形同虚设
    doLast {
        val dir = reportDir.get().asFile
        check(dir.exists() && dir.listFiles()?.any { it.name.endsWith(".xml") } == true) {
            "未找到 detekt 报告：${dir.absolutePath}"
        }
        // detekt 的 XML 报告里 finding 的 source 带规则名，直接扫规则名即可，
        // 比解析 severity 更稳（死代码问题不应因严重级别调低而漏网）。
        val deadCodeRules = listOf(
            "UnusedPrivateMember", "UnusedPrivateProperty", "UnusedPrivateClass",
            "UnusedParameter", "UnusedImports", "UnusedReturnValue",
        )
        val hits = dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".xml") }
            .flatMap { file -> file.readLines() }
            .filter { line -> deadCodeRules.any { it in line } }
        check(hits.isEmpty()) {
            buildString {
                appendLine("检出 ${hits.size} 处死代码候选（PRD T5 要求 0 处）：")
                hits.take(20).forEach { appendLine("  " + it.trim().take(160)) }
                if (hits.size > 20) appendLine("  …… 其余 ${hits.size - 20} 处省略")
                appendLine()
                appendLine("处理方式：确属死代码则删除；确由 XML/反射/序列化用到，")
                appendLine("则在 config/detekt/detekt.yml 的 DeadCodeExemptions 里逐条登记理由。")
            }
        }
        logger.lifecycle("[死代码门禁] verifyNoDeadCode 通过：0 处未使用的私有成员")
    }
}

jacoco {
    toolVersion = "0.8.12"
}

android {
    namespace = "com.github.kevinvane.daytimeline.library"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = false
    }
    lint {
        // T6：平台规范检查 0 错误 0 警告。library 是对外发布的 AAR，
        // 暴露给接入方的 API 一律不得带警告。
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // AD-05：无障碍虚拟视图（Q8 / UF-002 / FI-016）。AndroidX 官方，非第三方。
    implementation(libs.androidx.customview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

/**
 * 覆盖率门禁（PRD §12.4 / T4）。
 *
 * 「核心逻辑 ≥ 90%」与「全库 ≥ 75%」两条线分别卡，任一不达标即构建失败。
 * 这里刻意直接读 JaCoCo 产出的 XML 而不是用 `JacocoCoverageVerification`：
 * 后者在本工程的 AGP 版本下 DSL 变动频繁，且无法方便地只统计 `core` 子集。
 */
// JaCoCo 的 package name 用斜杠分隔，且不带结尾斜杠
val coreCoveragePrefix = "com/github/kevinvane/daytimeline/library/core"

// Android 插件不会自动创建 JaCoCo 聚合报告任务，显式建一个
val jacocoReportTask = tasks.register<JacocoReport>("jacocoTestDebugReport") {
    group = "verification"
    description = "生成 JaCoCo 覆盖率报告（debug）"
    dependsOn("testDebugUnitTest")
    reports.xml.required.set(true)
    reports.html.required.set(true)
    classDirectories.setFrom(
        files(
            // Kotlin 编译产物；本工程无 Java 源，故只需这一处
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        ),
    )
    executionData(tasks.named<Test>("testDebugUnitTest").get())
}

/**
 * 覆盖率门禁（PRD §12.4 / T4）。
 *
 * 「核心逻辑 ≥ 90%」与「全库 ≥ 75%」两条线分别卡，任一不达标即构建失败。
 * 这里刻意直接读 JaCoCo 产出的 XML 而不是用 `JacocoCoverageVerification`：
 * 后者在本工程的 AGP 版本下 DSL 变动频繁，且无法方便地只统计 `core` 子集。
 */
fun registerCoverageGate(
    gateName: String,
    gateDescription: String,
    minimumRatio: String,
    coreOnly: Boolean,
) {
    tasks.register(gateName) {
        group = "verification"
        description = gateDescription
        dependsOn("testDebugUnitTest", jacocoReportTask)
        val reportFile = layout.buildDirectory
            .file("reports/jacoco/jacocoTestDebugReport/jacocoTestDebugReport.xml")
        inputs.file(reportFile)
        doLast {
            val file = reportFile.get().asFile
            check(file.exists()) { "未找到 JaCoCo 报告：${file.absolutePath}" }
            val doc = DocumentBuilderFactory.newInstance().apply {
                isValidating = false
                // JaCoCo 的 XML 带 DTD 声明，必须关闭外部 DTD 加载，否则会去联网下载
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(file)
            val counters = doc.getElementsByTagName("counter")
            var covered = 0L
            var total = 0L
            for (i in 0 until counters.length) {
                val node = counters.item(i)
                if (node.attributes.getNamedItem("type").nodeValue != "INSTRUCTION") continue
                if (coreOnly) {
                    // counter 的祖先链是 method -> class -> package，必须一路向上找 package
                    var p: org.w3c.dom.Node? = node.parentNode
                    while (p != null && p.nodeName != "package") p = p.parentNode
                    val pkg = p?.attributes?.getNamedItem("name")?.nodeValue.orEmpty()
                    if (!pkg.startsWith(coreCoveragePrefix)) continue
                }
                val missed = node.attributes.getNamedItem("missed").nodeValue.toLong()
                val hit = node.attributes.getNamedItem("covered").nodeValue.toLong()
                covered += hit
                total += missed + hit
            }
            check(total > 0) {
                "统计范围内没有可执行类（coreOnly=$coreOnly，counter 总数=${counters.length}）"
            }
            val percent = BigDecimal(covered)
                .multiply(BigDecimal(100))
                .divide(BigDecimal(total), 2, RoundingMode.HALF_UP)
            val required = BigDecimal(minimumRatio).multiply(BigDecimal(100))
            logger.lifecycle("[覆盖率门禁] $gateName 实际 $percent%  要求 ${required}%  ($covered/$total)")
            check(percent >= required) {
                "$gateDescription 未达标：实际 ${percent}%，要求 ≥ ${required}%"
            }
        }
    }
}

registerCoverageGate(
    gateName = "verifyCoreCoverage",
    gateDescription = "核心逻辑（core 包）覆盖率门禁，低于 90% 即失败（PRD T4）",
    minimumRatio = "0.90",
    coreOnly = true,
)
registerCoverageGate(
    gateName = "verifyAllCoverage",
    gateDescription = "全库覆盖率门禁，低于 75% 即失败（PRD T4）。" +
        "注意：该口径包含 View/绘制层，只能由仪器测试（需设备）覆盖；" +
        "JVM 单测只能覆盖 core，故本任务在无设备环境下必然不达标。",
    minimumRatio = "0.75",
    coreOnly = false,
)
