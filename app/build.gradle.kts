plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.hwkbd.ime"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.hwkbd.ime"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "0.6.0"
    }

    signingConfigs {
        // 项目密钥(提交在仓库,个人项目权衡:本地/CI 签名一致 > 私密性)。
        create("project") {
            storeFile = rootProject.file("hwkbd.keystore")
            storePassword = "REDACTED-SIGNING-PASSWORD"
            keyAlias = "gaia"
            keyPassword = "REDACTED-SIGNING-PASSWORD"
        }
        // CI/CD:环境变量注入正式签名时优先于项目密钥
        val ksPath = System.getenv("SIGNING_KEYSTORE")
        val storePass = System.getenv("SIGNING_STORE_PASSWORD")
        if (!ksPath.isNullOrEmpty() && !storePass.isNullOrEmpty() && file(ksPath).exists()) {
            create("ci") {
                storeFile = file(ksPath)
                storePassword = storePass
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: storePass
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // debug 也用项目密钥:本地与 CI 构建签名一致,install -r 不再冲突
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("project")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // CI 注入的正式签名优先;否则项目密钥,保证 release 包可直接安装
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("project")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference:1.2.1")
    // 协程:目前只有 ui.RenderPump 在用(按键路径的渲染合并,见 dev.hwkbd.ime.ui.RenderPump)——
    // 主线程上 yield 一帧,再把这一轮里多次 render() 合成一次重绘。
    // 版本锁 1.8.1 = 与本项目 Kotlin 1.9.24 配套的稳妥版。体积:coroutines-android 自身几 KB,
    // 大头是 coroutines-core(APK 7.4MB → 约 +1.2MB dex)。
    // (修饰键手势那条 debounce 流 2026-09-30 随"暂定态"规则一起删了,见 ModifierGestures。)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // **物理引擎**(2026-10-02 用户「使用物理引擎重构手势和动画」)。
    //
    // 选 `androidx.dynamicanimation` 而不是自己写积分器,理由是它就是 Android 官方
    // 那套 `SpringAnimation`/`FrictionAnimation` 的实现(与 Compose 的 spring 同源):
    // - **弹簧**:`SpringAnimation` 带**速度连续**语义,打断时换目标不跳变 ——
    //   这正是此前 `ValueAnimator` + 固定时长做不到、用户反复报「打断后反向动画」
    //   「消失动画不自然」的那一条;
    // - **摩擦/惯性**:`FrictionAnimation` 是 2D 摩擦模型,与本项目 [Fling] 的
    //   匀减速 + 库仑摩擦同源,但由官方维护、数值稳定性有保证;
    // - 它在 `androidx.animation` 命名空间下、AGP 官方库,minSdk 21 起可用
    //   (本项目 minSdk 26),**不引入任何第三方 or native 代码** —— APK 体积影响可忽略,
    //   也不用在 keystore 之外多背一份信任。
    //
    // 代价:它是 Android 依赖,所以 [dev.hwkbd.ime.ui.Spring] 那种纯 Kotlin 积分器
    // 无法在 JVM 单测里直接跑官方实现。折中办法见 [SpringBridge] —— 把**参数语义**
    // (响应/阻尼比 ↔ stiffness/damping、落点判定)抽成纯函数单测,积分交给官方。
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")

    testImplementation("junit:junit:4.13.2")
    // 手势流化的时序单测:runTest 虚拟时间
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
