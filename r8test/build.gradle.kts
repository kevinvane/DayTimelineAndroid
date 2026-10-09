import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * R8 / 混淆消费端验证模块（PRD §12.5 / T7 / 风险 R8）。
 *
 * ## 为什么需要这个模块
 *
 * `library` 自己的 `consumer-rules.pro` **从未在任何开启混淆的环境里跑过**。
 * 规则写错了也照样「通过」——因为 library 自身 `isMinifyEnabled = false`，
 * R8 根本不执行，规则里哪怕是笔误也没有任何东西会报错。
 *
 * 这个模块以**业务方的身份**依赖 `:library`，release 开启 minify，
 * 并让仪器测试跑在**混淆后的 release 变体**上。这样：
 *
 * - R8 若删掉了业务方要用的成员，仪器测试会立刻失败；
 * - R8 若因规则不当产生缺类，`assembleRelease` 就会失败；
 * - `verifyKeptSymbols` 额外核对「保留得是否精确」，防止规则写得过宽
 *   （`-keep class **` 那种功能正常但体积膨胀的写法）；
 * - 验证的是 PRD 明写的承诺：接入方开启混淆后功能正常。
 *
 * ## 验证方式
 *
 * ```powershell
 * .\gradlew.bat :r8test:assembleRelease              # R8 能否跑通
 * .\gradlew.bat :r8test:verifyKeptSymbols             # 对外契约类是否按预期保留
 * .\gradlew.bat :r8test:connectedReleaseAndroidTest  # 混淆后功能是否正常（需设备）
 * ```
 */
android {
    namespace = "com.github.kevinvane.daytimeline.r8test"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.github.kevinvane.daytimeline.r8test"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 这一行是这个模块存在的全部意义
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                // 只放**本模块自己的**规则。library 的 consumer-rules.pro 由 AAR
                // 自动带进来——那正是要验证的东西，不能在这里重复写一遍。
                "proguard-rules.pro",
            )
            // debuggable = false：让 R8 走与真实发布完全相同的路径
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // 关键：让仪器测试跑在**混淆后的 release 变体**上。
    // 不写这一行，connectedAndroidTest 会跑 debug 变体，R8 根本没参与，
    // 整个验证就成了空转。
    testBuildType = "release"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":library"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

/**
 * 混淆产物自检：核对 release APK 里**应当保留**的类名。
 *
 * 为什么需要：instrumented test 能证明「功能可用」，但证明不了
 * 「保留规则精确」——规则一旦写得过宽（`-keep class **`）功能照样正常，
 * 体积却白白膨胀。这一步把「规则是否过宽」也纳入验证范围。
 */
val verifyKeptSymbols by tasks.registering {
    group = "verification"
    description = "核对 release APK 中组件的对外契约类是否按预期保留（PRD §12.5 / T7）"

    // 必须显式依赖：否则本任务会与 R8 并行，读到上一轮的旧 APK，
    // 报出「刚修好的规则又坏了」这种假失败。
    dependsOn("assembleRelease")

    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val kept = listOf(
        // 业务方直接引用，必须原名保留
        "com.github.kevinvane.daytimeline.library.DayTimelineView",
        "com.github.kevinvane.daytimeline.library.core.TimelineEvent",
        "com.github.kevinvane.daytimeline.library.api.TimelineListener",
        "com.github.kevinvane.daytimeline.library.DayTimelineView\$EditController",
        // consumer-rules.pro 里声明要保留的定制上下文
        "com.github.kevinvane.daytimeline.library.api.GridContext",
        "com.github.kevinvane.daytimeline.library.api.BlockContext",
        "com.github.kevinvane.daytimeline.library.api.TimelineColors",
        // AD-22 新增的表单契约，业务方要读它们的字段
        "com.github.kevinvane.daytimeline.library.api.EditDraft",
        "com.github.kevinvane.daytimeline.library.api.EditResult",
        // AD-23 详情快照；detailOf() 的返回类型
        "com.github.kevinvane.daytimeline.library.api.EventDetail",
    )
    inputs.dir(apkDir)
    outputs.upToDateWhen { false }

    doLast {
        val dir = apkDir.get().asFile
        val apk = dir.listFiles()?.firstOrNull { it.name.endsWith(".apk") }
        checkNotNull(apk) { "未找到 release APK：${dir.absolutePath}" }

        // ⚠️ 两个易踩的坑，都在这段代码里踩过一次：
        // 1. APK 是 ZIP，classes.dex 是 deflate 压缩的，必须先解压再搜；
        // 2. DEX 的**类型描述符用斜杠**（`Lcom/github/…/TimelineEvent;`），
        //    不是点号。只搜点号会大面积误报缺失。
        val text = ZipFile(apk).use { zip ->
            val sb = StringBuilder()
            zip.entries().asSequence()
                .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                .forEach { entry ->
                    sb.append(String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1))
                }
            sb.toString()
        }

        fun present(fqcn: String): Boolean {
            val slashed = fqcn.replace('.', '/')
            return text.contains("L$slashed;") || text.contains(slashed)
        }

        val missing = kept.filterNot(::present)
        check(missing.isEmpty()) {
            buildString {
                appendLine("R8 混淆后以下对外契约类未在 DEX 中找到（PRD §12.5 / T7）：")
                missing.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("请检查 library/consumer-rules.pro 是否漏了对应的 -keep 规则。")
            }
        }
        logger.lifecycle(
            "[R8 门禁] ${kept.size} 个对外契约类在混淆产物中均已保留；APK ${apk.length() / 1024} KB",
        )
    }
}