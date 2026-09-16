import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/*
 * release 签名参数从 `local.properties` 读。
 *
 * ⚠️ 密钥**不进版本库**：`local.properties` 与 `keys/` 都在 `.gitignore` 里。
 *    这是 release 签名唯一正确的存放方式 —— 密钥一旦进 Git 历史，就只能换密钥，
 *    而换密钥意味着所有已安装的用户必须卸载重装。
 *
 * 之所以做成「有就用、没有就警告」而不是硬失败：本文件在没配密钥的机器上
 * （比如 CI 只跑 assembleDebug）也要能正常配置通过。
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val ksFile = localProps.getProperty("beevideo.keystore.file")
val ksAlias = localProps.getProperty("beevideo.keystore.alias")
val ksStorePassword = localProps.getProperty("beevideo.keystore.storePassword")
val ksKeyPassword = localProps.getProperty("beevideo.keystore.keyPassword")
val hasReleaseSigning = listOf(ksFile, ksAlias, ksStorePassword, ksKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.cycling.beevideo"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.cycling.beevideo"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = ksStorePassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            /*
             * 签名。没有它 `assembleRelease` 产出的是**未签名 APK** ——
             * 那种包 adb install 会直接拒（INSTALL_PARSE_FAILED_NO_CERTIFICATES），
             * 而 Gradle 只会打一行不显眼的 warning，很容易误以为构建成功就能装。
             */
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "[BeeVideo] local.properties 里没有 release 签名参数，" +
                        "assembleRelease 将产出**未签名** APK（装不上）。" +
                        "需要先跑一次 keystore 生成，见 local.properties 里的 " +
                        "beevideo.keystore.* 四项。"
                )
            }

            optimization {
                enable = true
                keepRules {
                    // AGP 自带的默认规则（desugaring / Compose 等）仍然带上
                    files.add(getDefaultProguardFile("proguard-android-optimize.txt"))
                    // 项目自己的规则。⚠️ 内容不是可有可无的，见文件头部注释
                    files.add(file("proguard-rules.pro"))
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // 纯 JVM 单测里 `android.util.Log` 是 android.jar 的**桩**，调用即抛
            // "Stub!"。宿主侧在 spider 调用失败时会打一行 Log.w，那属于正常路径，
            // 不该让单测因为"打了条日志"而红。开启后桩方法返回默认值（这里即空操作）。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Media3 / ExoPlayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    // Image loading
    implementation(libs.coil.compose)

    // 网络：站点请求 + CatVod 兼容层（com.github.catvod.net.OkHttp）的底座
    implementation(libs.okhttp)
    // Gson：**不是给我们的业务代码用的**，是真实 csp jar 的外部依赖。
    // 反汇编 7 个真实 jar 后确认 com/google/gson 被 7/7 引用，且没有一个把 Gson
    // 打进 jar，说明这一层必须由宿主提供。业务代码仍然只用 org.json，别在这里加戏。
    implementation(libs.gson)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    // 真实 org.json：android.jar 里的是桩，JVM 单测直接抛 "Stub!"
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
