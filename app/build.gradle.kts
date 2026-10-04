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
        // 0.7.1 = 纯补丁:修 0.7.0 里「翻页结构性失效」那一个功能缺失。
        // 0.7.0 已发布,不能就地重发(见 docs/versioning.md:同一个 versionCode
        // 配不同字节 ⇒ Android 拒装升级,且同一下载链接在不同时刻给出不同的包)。
        // 0.7.2 = 修 0.7.1 的 R8 事故:release 包里 7 个 JNI 入口被改名,
        // 真机上拼音引擎整个不可用(见 docs/rime.md 与 app/proguard-rules.pro 第 2 节)。
        // E2E 跑 debug 包(不过 R8),所以这个问题在 CI 上结构性看不到 ——
        // 0.7.2 起 release.yml 在发布之前跑 tools/release/check-jni-symbols.py。
        // 0.7.3 = 修翻页:松手时落点用错了函数(`Landing.project` 是**惯性投影**,
        // 不是「走完」),于是 1440px 的整页行程从来没走完,画面是跳过去的。
        // 用户报「松手时翻不过去」「翻过去/收回的动画不对」「之前翻頁困難」。
        // 顺带:提交与收回分成两支弹簧(欠阻尼 / 过阻尼)。
        // 0.7.4 = 手感两档:
        //   - 翻页提交弹性 ζ 0.72 → 0.9 → 0.95(1440px 行程上过冲 55.3px → 2.2px → 0.1px)
        //   - 死区拆成横滑 4dp / 上滑 8dp,翻页手指总行程 24px → 16px,上滑一字未动
        // 顺带修第三方署名(换内核后 jieba/pinyin-pro 早已不在包里,署名全是错的)。
        // 0.7.5 = 修「小幅多次振盪」:松手时提交线读错量纲。
        // pageDragEnd 先清 externalDrive 再分发,于是 endPageDrag 里读到的提交线
        // 掉回「行宽 × 0.22」(Titan 2 上 302px)而不是键盘面档位的 8px。
        // 后果:划过 8px 当帧翻页 → 松手又判成没过线 → 撤销 → 画面来回抽动;
        // 附带「甩也甩不动」(byFling 要求惯性落点 ≥302px,快甩只有几十 px)。
        // 0.7.6 = 翻页动画时长改成**按剩余行程给**,不再写死。
        // 根因:翻页是全项目位移最大的动画(最多 1440px),却用了最短的时长
        // (0.26s ≈ 5540 px/s),而飞字起飞 52px 用 0.22s(≈236 px/s)—— 速度差 20 倍。
        // 用户原话「一直都很快」是准确的。现在 response = clamp(行程/1800, 0.28s, 0.60s),
        // 收回(滑回原页)按用户要求一起慢。
        versionCode = 27
        versionName = "0.7.8"

        // **release 只编 arm64-v8a**(2026-10-03 用户「只需要 arm64 版本」)。
        //
        // librime 的 `.so` 是 3.5MB/ABI,两个 ABI 就多背 3.5MB —— 而用户只有 Titan 2
        // (arm64),x86_64 对正式包毫无用处。x86 / armeabi-v7a / riscv64 同理,全不编。
        //
        // x86_64 只在 debug 里加回来给 E2E 的模拟器用,见 buildTypes 里的注释。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    /**
     * NDK 版本。**必须与 CI 装的完全一致**(release.yml 里 sdkmanager 那行)。
     *
     * 不同 NDK 编出来的 .so 行为会有差异(运行库版本、C++ 标准库实现细节),
     * 本地能跑线上崩这类问题几乎无法从 Kotlin 侧看出来。所以它只写一个数,
     * 另一处由 release.yml 的 "Report NDK" 步骤打出来对账。
     */
    ndkVersion = "27.1.12297006"

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

            // **debug 加回 x86_64**:E2E 流水线跑在 x86_64 模拟器上
            // (见 e2e.yml 的 `system-images;android-34;default;x86_64`),
            // 只编 arm64 的话模拟器装不上。
            //
            // buildType 里的 abiFilters 会**取代** defaultConfig 的,所以这里必须写全 ——
            // 只写 "x86_64" 会让 debug 包里没有 arm64,本地真机插不上。
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
        // **混淆 + 资源压缩**(2026-10-02「应用包需要安全加固 防止重新打包」)
        //
        // 之前是 `isMinifyEnabled = false`,也就是**完全不混淆** —— jadx 打开
        // 类名方法名全在,改一个分支就能去掉任何校验。
        //
        // 代价与配套(缺一不可):
        // - 崩溃栈会变成 `a.b.c(SourceFile:42)` ⇒ 必须有 mapping 才能定位
        //   (CI 上传为 artifact,见 release.yml;崩溃报告落到应用私有目录);
        // - keep 规则见 proguard-rules.pro,漏一条就是运行期 ClassNotFound。
        //
        // `optimize` 版比默认更激进(方法去内联/去虚拟化),所以那些 keep 更关键。
        isMinifyEnabled = true
        isShrinkResources = true
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
        // EXPECTED_SIGNER 用 buildConfigField 注入,所以要开 BuildConfig
        buildConfig = true
    }

    defaultConfig {


    }

    // =============================================================================
    // native:librime(拼音引擎内核)
    //
    // 2026-10-02 起换引擎。librime 是 C++ 库,必须用 NDK 交叉编译成 .so 打进 APK。
    // 配方与 `adam-ikari/term-ime` 同源(那个项目已经把 librime 在无桌面环境下的
    // 三处硬骨头解决了:改用 std::regex 而非 boost::regex、vendored deps 分两段编、
    // leveldb 不带 snappy)—— 逐条理由见 app/src/main/cpp/CMakeLists.txt 的注释。
    //
    // **只编 arm64-v8a + x86_64**:
    // - arm64-v8a: Titan 2 与绝大多数现代安卓设备;
    // - x86_64: 模拟器(E2E 流水线要用),**不进正式发布包**(见下面 packaging 排除)。
    //   x86 与 armeabi-v7a 都不编 —— 用户只有 Titan 2,编了只是让包变大、CI 变慢。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }


    packaging {
        jniLibs {
            // x86_64 只为模拟器存在,不进给用户下的包(约省 1.2MB)。
            // 用 keep 排除而不是删掉 abi:ABIs 列表里要留着它,E2E 才装得上。
            keepDebugSymbols += setOf("**/libc++_shared.so")
        }
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
