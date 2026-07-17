plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tombstonex"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.tombstonex"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        // 暴露版本号给 UI 层（AboutScreen / SettingsScreen）
        buildConfigField("String", "VERSION_NAME", "\"${versionName}\"")
        buildConfigField("int", "VERSION_CODE", "${versionCode}")
        buildConfigField("String", "MODULE_ENTRY", "\"com.tombstonex.hook.MainHook\"")
        buildConfigField("String", "LOG_PATH", "\"/data/system/TombstoneX/current.log\"")
        buildConfigField("String", "CONFIG_DIR", "\"/data/system/TombstoneX\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 优先使用 release 签名，不存在时回退到 debug（用于 CI 构建）
            val keystoreFile = rootProject.file("keystore.properties")
            if (keystoreFile.exists()) {
                val keystoreProperties = java.util.Properties()
                keystoreProperties.load(keystoreFile.inputStream())
                signingConfig = signingConfigs.create("release") {
                    keyAlias = keystoreProperties["keyAlias"] as String
                    keyPassword = keystoreProperties["keyPassword"] as String
                    storeFile = file(keystoreProperties["storeFile"] as String)
                    storePassword = keystoreProperties["storePassword"] as String
                }
            } else {
                logger.warn("keystore.properties not found, falling back to debug signing for release build")
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        // 只 disable 具体的 lint 规则，而非整体关闭检查
        disable += setOf(
            "MissingTranslation",
            "ExtraTranslation",
            "GoogleAppIndexingWarning",
            "GradleDependency",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(project(":xposed-stub"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.core.ktx)

    debugImplementation(libs.compose.ui.tooling)
}
