plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
}

detekt {
    // 与 library 同一份配置，保证「代码风格 0 违规」覆盖整个仓库
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    ignoreFailures = false

    // 与 `:library:detekt` 逐字一致地列出三个源集。
    //
    // **曾经只写 `src/main/java`**，注释却声称「覆盖整个仓库」——实际不成立：
    // 仪器测试与 JVM 单测全部游离在静态检查之外。代价是实打实的：
    // `MainActivityCancelPathTest` 里一个没人用的 `closeSoftKeyboard` 导入就长期躺着，
    // detekt 的 `UnusedImports` 从来没机会看到它。
    //
    // 关键在于**两类源集的检查规则不同**：`src/androidTest` 里写的是「怎么驱动界面」，
    // 静态检查对这类代码比对生产代码更有价值，不是可以省掉的那部分。
    // `app/src/test` 目前不存在，`fileTree` 对不存在的目录返回空集，不会因此报错——
    // 等真加上了 JVM 单测时，它自动纳入检查，不需要再改这里。
    source.setFrom(
        files(
            fileTree("src/main/java") { include("**/*.kt") },
            fileTree("src/test/java") { include("**/*.kt") },
            fileTree("src/androidTest/java") { include("**/*.kt") },
        ),
    )
}

android {
    namespace = "com.github.kevinvane.daytimeline.app"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.github.kevinvane.daytimeline.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
    detektPlugins(libs.detekt.formatting)
    implementation(project(":library"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.contrib)
}