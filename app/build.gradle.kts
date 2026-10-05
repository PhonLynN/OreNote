plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.phonlynn.oreplan"
    // 最新 AndroidX（compose-ui 1.12、core-ktx 1.19 等）要求 AAR 元数据 compileSdk >= 37。
    // 本机已装 platforms/android-37.0。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.phonlynn.oreplan"
        // 用户手机为 Android 16（API 36），因此 minSdk / targetSdk 都为 36；
        // compileSdk 只影响可用的编译期 API，不影响可安装范围。
        minSdk = 36
        targetSdk = 36
        //
        // 版本规则（用户 2026-09-16 定）：
        //   versionName 从 0.1.0 开始，每次更新末位 +1，**不进位**（0.1.9 → 0.1.10 → 0.1.11…）；
        //   直到用户宣布白板系统彻底完工，才升为 0.2.0。
        //   versionCode 必须单调递增（否则无法覆盖安装），与 versionName 解耦，每次 +1。
        // ⚠️ 2026-09-30：这里曾被手动跳到 450。
        // 原因：我为了排查 #34 连续出了几个包（0.2.22~0.2.24，code 到 444）后又把源码
        // 回退到 0.2.21 —— 源码的 code(442) 于是低于设备上已装的 code(444)，
        // 触发 INSTALL_FAILED_VERSION_DOWNGRADE（release 包不可 debuggable，`-d` 无效）。
        // 处理：把 code 跳到 450 让它重新高于设备值。**versionCode 必须单调递增**。
        //
        // ⚠️ 2026-10-01：同样的情形又发生了一次，这次跳到 500。
        // 原因：待办列表重写期出包到 code 492，之后源码整体回滚到 0.2.36-stable 基线
        // （code 463），于是新包一律低于设备上已装的 492 ⇒ **装不上**。
        // versionName 也从 0.2.38 提到 0.2.67（跳过 0.2.39~0.2.66：
        // 那段是已废弃的重写期，代码保存在分支 backup/main-0.2.66-* 与标签 archive-0.2.66-*）。
        //
        // ⚠️ 2026-10-02：**0.3.0 规划模块重做**开始。
        // 用户要求「不要删除原有版本」= 必须能**覆盖安装**、测试数据不丢，所以：
        //   · versionCode 继续单调 +1（501 → 502），绝不回落；
        //   · applicationId / 签名都不动（release 用 debug 密钥，与已装包同包名同签名）；
        //   · 回退点：tag `v0.2.67-stable` + 分支 `backup/plan-pre-0.3.0`
        //     + 已出好的 dist/OreNote-0.2.68-release.apk（code 501）。
        // ⚠️ 2026-10-04：这里**跳到 700 以上**，不是笔误。
        //
        // 原因：同一份工作区里同时有两个会话在出包，各自都从同一个计数器 `+1`。
        // 用户在两个会话之间来回装包，于是**先装到高号的那个、另一个就再也装不上**
        //（系统报「版本号更低」，直接拒绝降级）。第一次跳到 640 仍不够 ——
        // 对方紧接着就出了更高的号。
        //
        // 这次留出足够长的空档（≈60 次构建），让两边的包都能装上。
        // 这与上面 450 / 500 两次是同一种处理 —— **versionCode 只要求单调递增，允许跳号**。
        //
        // 注意：跳号之后，另一会话若出低于本值的包，需要
        // `adb install -r -d`（`-d` = 允许降级）才能装。
        versionCode = 718
        versionName = "0.4.16"
    }

    buildTypes {
        // ---- release：**可安装的正式包**（2026-09-25 性能改造） ----
        //
        // 为什么要有它：以前 release 既没开 R8、也没配签名，产物**装不上**，
        // 所以一直只发 debug 包。而 debug 包 + 冷安装没有 AOT profile，
        // 表现就是「冷启动后先卡几分钟、用着用着自己变好」（解释执行 → JIT → 后台 AOT）。
        // 这正是用户报的「全局卡顿」的指纹。
        //
        // 关键约定：
        //  · 签名用 **debug 密钥**（本机 ~/.android/debug.keystore），
        //    applicationId 也不加后缀 —— 所以它与 debug 包**同包名同签名**，
        //    可以在已装 debug 包的机器上直接 -r 覆盖安装，数据不丢。
        //  · isDebuggable = false：否则和 debug 包一样会禁用部分 AOT 优化。
        //  · R8 开启 + 资源压缩：体积小、启动快。
        //    注意：反射/序列化要保留的规则都在 proguard-rules.pro（Hilt / Room / Kotlin 已带 consumer rules）。
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // debug 保持原样（仍可断点调试、仍用于日常联调）。
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // 单元测试。M1 的验收手段就是这些纯 JVM 测试 —— 时间与树结构的边界情况
    // 必须在没有模拟器的情况下也能快速反复验证。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json 在 Android 上由框架提供，单元测试里需要一份 JVM 实现才能跑编解码测试
    testImplementation(libs.org.json)
}
