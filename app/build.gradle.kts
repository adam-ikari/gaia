plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * `git rev-parse --short HEAD`,取不到就返回 `"nogit"`。
 *
 * ## 为什么失败时不抛异常、也不返回空串
 *
 * beta 的 `versionNameSuffix` 依赖它。取不到时如果抛异常,整个构建失败 ——
 * 而「这台机器上编不出 beta」和「构建配置错了」是完全不同的两件事,
 * 混在一个异常里会让后者更难查。
 *
 * 返回 `"nogit"` 而不是 `""`:空串会让 `versionName` 变成 `0.7.11-beta.`,
 * 那个尾巴看起来像**成功**了。而 `nogit` 一眼就知道「这个标识不可信」。
 */
fun gitShortHash(): String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.matches(Regex("[0-9a-f]{7,40}")) } ?: "nogit"

/**
 * 工作区有没有未提交的改动。
 *
 * **不是为了洁癖**:有改动时 `git rev-parse HEAD` 那个 hash **不代表实际编出来的
 * 字节**。把这种包标成 `0.7.11-beta.7a8e59d` 就是骗人 —— 用户按这个 hash 报 bug,
 * 他编不出同样的东西,复现不了。所以 `versionNameSuffix` 里要带 `.dirty`。
 */
fun gitDirty(): Boolean = runCatching {
    providers.exec {
        commandLine("git", "status", "--porcelain")
    }.standardOutput.asText.get().trim().isNotEmpty()
}.getOrDefault(false)

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
        // **这里承袭已发布的版本号,不是「下一个要发的」。**
        //
        // 版本号只允许出现在**发版那一个 commit** 里(见 docs/branching.md)。
        // 于是恒等式成立:master 的版本号 == 最新已发布版本的版本号。
        //
        // 为什么:master 上一度停在 0.7.10 整整 14 个 commit,而 0.7.10 从未发布
        // —— 改它的是 `3eac2f1`,一个跟版本无关的功能 commit(顺手改的)。
        // 代价不是历史难看,是**用户报 bug 说「我用的 0.7.10」时这句话定位不到
        // 任何已发布的产物**,而 master 编出来的包也自称一个不存在的版本。
        //
        // 判据是 `tools/release/gate.sh` 的「版本号漂移」那一步,它比的是
        // **值**(`ver_of` 解 HEAD 与最高 tag 的 build.gradle.kts),不是
        // 「哪个 commit 碰过这个文件」—— 后者会把顺手修 typo 也算成一次 bump。
        // 退回已发布值:`v0.7.10` 是目前唯一发出去的版本(见上面「唯一来源」那段)。
        //
        // 之前这里是 30/0.7.11,而 0.7.11 **从未发布** —— 我在 `9f93443`
        // 那个 commit 里顺手提的,之后又推了 5 个 commit。
        // 于是 master 停在一个自称「0.7.11」而 0.7.11 不存在的状态,
        // `gate.sh` 的漂移检查正好把它抓出来(它原本会报成一个错值,已修)。
        //
        // 而 dev/beta 渠道**每次都从 master 编**,所以这个谎会被放大成
        // 「站点上那个 0.7.11-dev.xxx 其实是 0.7.10 的后代,却自称 0.7.11」。
        //
        // ⇒ master 承袭已发布版本;0.7.11 由**发版 commit** 引入。
        //   代价是 dev 渠道会显示 `0.7.10-dev.<hash>` —— 而那是**真的**:
        //   它确实是 0.7.10 之后、还没成为任何发布的一版。
        versionCode = 30
        versionName = "0.7.11"

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
        // ---------------------------------------------------------------------
        // beta = **测试版**:与正式版是**两个不同的应用**(2026-10-06)
        //
        // ## 为什么要独立成一个应用,而不是同一个应用的另一个下载链接
        //
        // 同一个 applicationId 的话,测试版与正式版**互相顶掉**:
        // Android 按 `versionCode` 判升级,而测试包是本地编的、字节与 CI 编的
        // 必然不同 ⇒ 同一号不同字节 ⇒ 用户从正式版装测试版会撞
        // `INSTALL_FAILED_VERSION_DOWNGRADE`,反之亦然。
        // **用户被迫在「试新版」与「保住现在这个」之间二选一** ——
        // 而测试版的意义就是让人两者都能要。
        //
        // `applicationIdSuffix = ".beta"` 把它变成 `dev.hwkbd.ime.beta`,
        // 于是:
        //   · 与正式版**并存**,互不覆盖,各自的数据与设置分开;
        //   · `versionCode` 各自独立计数,测试版可以随便发;
        //   · 输入法列表里是两个条目(`盖亚输入法` / `盖亚输入法 β`),
        //     可以随时切回去。
        //
        // **顺带解掉了「本地侧载烧号」那个问题**:正式包已发的 29 不会被
        // 测试版消耗掉,不必为了试一个修复去跳正式版本号。
        //
        // ## versionName 带 git hash
        //
        // `versionNameSuffix = "-beta.<hash>"`,来源是 `git rev-parse --short HEAD`。
        // 为什么必须带:测试版最需要的不是版本号,是**可追溯性** ——
        // 用户报「β 版有问题」时,「哪个 commit」是第一个要回答的问题。
        // 而 `versionName` 是唯一在**设备上、不用连电脑**就能读到的标识
        // (设置 → 应用 → 版本)。
        //
        // 工作区不干净时后缀加 `.dirty`:那个 hash **不代表实际编出来的内容**,
        // 不标出来就是骗人。
        create("beta") {
            initWith(getByName("release"))

            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta.${gitShortHash()}${if (gitDirty()) ".dirty" else ""}"

            // ABI 跟 release 一样只编 arm64 —— beta 发给真机,模拟器用 debug。
            // (initWith 已复制过来,这里写明是为了让读的人知道它是刻意的。)
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }

            // **R8 必须显式打开,不能靠 initWith**。
            //
            // `initWith(getByName("release"))` **没有**把 `isMinifyEnabled` 带过来:
            // 实测 beta 编出来 9.1MB 而 release 6.9MB,`app/build/outputs/mapping/beta/`
            // 里**没有 mapping.txt** ⇒ beta 根本没混淆过。
            // 原因:AGP 对 buildType 的配置是延迟生效的,`initWith` 执行时
            // release 块里的赋值还没落到 DSL 对象上。
            //
            // 所以下面两行是**写死的**,不是继承来的:
            //   - `initWith` 容易让人以为「跟 release 一样」,而**读代码看不出来它没跟**;
            //   - beta 若不过 R8,就测不出「改名导致的 UnsatisfiedLinkError」那类问题,
            //     而那正是正式版最贵的一类事故(2026-10-04:v0.7.1 有 7/14 个入口被削掉)。
            // ⇒ 判据是产物,不是配置:assembleBeta 之后必须有 mapping/beta/mapping.txt。
            //   见 tools/release/gate.sh 的 check_beta_r8。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("project")

            matchingFallbacks += listOf("release")
        }

        // ---------------------------------------------------------------------
        // dev = **开发版**:master HEAD 的构建,与正式版/测试版都不同
        //
        // ## 它不是「发布」,所以它没有语义版本号
        //
        // 三条渠道里只有前两条是发布。dev 是「现在 master 长这样」——
        // 给它 `versionName = "0.7.12"` 会做两件坏事:
        //   1. **烧号**:`versionCode` 是发布计数器,dev 每跑一次就跳一格;
        //   2. **骗人**:一个从没发布过的号出现在设置页,用户会当成已发布的版本。
        //
        // ⇒ dev 的身份**只有 git hash**,一个语义版本号都不给。
        //   这也是「增加 git hash 版本」那条要求在本渠道的落法:
        //   hash 才是 dev 唯一诚实的标识。
        //
        // ## versionCode 固定,不跟发布计数器走
        //
        // 固定成一个大数(dev 是**另一个包**,与正式版/测试版各自独立计数,
        // 撞不上)。每次 dev 构建**覆盖**上一版而不是升级 —— 同 versionCode 的
        // `-r` 重装是允许的(Android 只拒绝**降低**),而 dev 本来就是「看最新」。
        // 若让它跟着发布计数器走,用户得装一串 0.7.12、0.7.13、0.7.14 才能到最新,
        // 而那些号从来没有对应任何发布。
        create("dev") {
            initWith(getByName("beta"))

            applicationIdSuffix = ".dev"
            // **用 `versionNameSuffix`,不覆盖 `versionName`**。
            //
            // 原本想直接写 `versionName = "dev-<hash>"`(一个语义版本号都不给),
            // 但 buildType 块里设不了 —— 覆盖它要走 `androidComponents` 的
            // `ApplicationVariantOutput.versionNameOverride`,而 **AGP 8.5.2 的
            // gradle-api 里没有那个类**(查过 jar:只有 `VariantOutput`,
            // 任何 jar 都不含 `versionNameOverride`)。要覆盖就得退回已废弃的
            // `applicationVariants`,不值当。
            //
            // ⇒ 退一步:保留基础版本号,后缀标明它从哪条线来 + 哪个 commit。
            //   `0.7.11-dev.abc1234` 携带的信息严格多于 `dev-abc1234`
            //   (多一个「它接在哪条发布线后面」),而且**不烧号** ——
            //   dev 是独立包 `dev.hwkbd.ime.dev`,versionCode **继承基础值**
            //   (别在这里写死数字 —— 基础值会随发版往前挪,写死的注释必然过期;
            //   原文写的是「继承 30」而当时基础值已经是 29)。
            //   三条渠道各自计数,互不干扰。
            //
            // 每次 dev 构建**覆盖**上一版(同 versionCode 的 `-r` 重装合法,
            // Android 只拒绝**降低**)。这正是「看最新」想要的语义。
            versionNameSuffix = "-dev.${gitShortHash()}${if (gitDirty()) ".dirty" else ""}"

            // **过 R8**(2026-10-06 用户定:「任何版本只要公开就需要混淆」)。
            //
            // 我原来在这里写的是 `isMinifyEnabled = false`,理由是
            // 「dev 是给开发/自测用的,验功能不验发布形态」。**那个理由混淆了两件事**:
            //
            //   · 本地自测的产物 —— 确实不需要混淆,快一点更好
            //   · **公开下载的字节** —— 必须混淆
            //
            // 而 dev 渠道**在站点上公开可下**(gaia 的 deploy.yml 会把
            // `dev-latest` 那个滚动 release 的资产取来放进 Pages)。
            // ⇒ 一旦公开,判据就变成「任何人都能拿到并反编译」,
            //   于是不过混淆等于把类名方法名全交出去,
            //   任何校验都能被 patch 掉(2026-10-02 用户「应用包需要安全加固
            //   防止重新打包」就是这件事)。
            //
            // **代价**:编 dev 变慢,而且崩溃栈会变成 `a.b.c(SourceFile:42)`
            // ⇒ 定位问题必须要 mapping。所以 mapping 一定要留着。
            //
            // 显式写死而不是继承:配置文件里写了什么不算数,
            // 见 tools/release/gate.sh 的 check_public_r8(它验的是产物)。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // dev 也编两个 ABI:本机若有模拟器可以直接装 dev 试(正式版只有 arm64)。
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "x86_64")
            }

            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("project")
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
