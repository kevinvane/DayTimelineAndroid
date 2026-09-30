import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory
import java.math.RoundingMode

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    jacoco
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
