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
        versionCode = 3
        versionName = "0.3.0"
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

    testImplementation("junit:junit:4.13.2")
}
