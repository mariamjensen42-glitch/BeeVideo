import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/*
 * release 签名参数从 `local.properties` 读。
 *
 * ⚠️ 密钥**不进版本库**：`local.properties` 与 `keys/` 都在 `.gitignore` 里。密钥一旦进了
 *    Git 历史就只能换密钥，而换密钥意味着所有已安装的用户必须卸载重装。
 *
 * 做成「有就用、没有就警告」而不是硬失败：本文件在没配密钥的机器上（比如 CI 只跑
 * assembleDebug）也要能配置通过。
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

        /*
         * 版本号可由命令行覆盖：
         *     -Pbeevideo.versionName=1.2.0 -Pbeevideo.versionCode=10200
         *
         * CI 打 tag 发版时靠它把 tag 写进 APK。不传则保持原值，**本机构建行为不变**。
         *
         * ⚠️ 不这么做的后果不是"显示难看"而已：Release 页面上写着 v1.2.0，装到手机上
         *    系统里却是 1.0，而版本号恰恰是用户报 bug 时唯一会报的那串数字 ——
         *    对不上账就没法定位到底是哪个包出了问题。versionCode 更要紧，它必须单调
         *    递增，否则已装用户根本升不了级（系统只认 code，不认 name）。
         */
        versionCode = providers.gradleProperty("beevideo.versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("beevideo.versionName").orNull ?: "1.0"

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
             * 没有它 `assembleRelease` 产出的是**未签名 APK**，`adb install` 会直接拒
             * （INSTALL_PARSE_FAILED_NO_CERTIFICATES），而 Gradle 只打一行不显眼的 warning，
             * 很容易误以为构建成功就能装。
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

/*
 * Room 的 schema 导出目录。
 *
 * 生成的 JSON **必须进版本库**：它是唯一能证明"第 N 版的表结构长这样"的东西，写第 N+1 版
 * 迁移时判断 `ALTER TABLE` 要补哪些列全靠它。不导出的话迁移只能靠记忆，而记错的表现是
 * **用户升级后数据静默丢失**。
 *
 * 用 ksp 参数而不是官方的 Room Gradle 插件：只为一件事多引一个插件不划算。
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Media3 / ExoPlayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    // 磁盘缓存：SimpleCache / CacheDataSource 在 datasource，StandaloneDatabaseProvider 在
    // database。虽然是 exoplayer 的传递依赖，但我们是**直接** new 它们的，按上面的理由自己声明。
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)

    // Image loading
    implementation(libs.coil.compose)

    // 网络：站点请求 + CatVod 兼容层（com.github.catvod.net.OkHttp）的底座
    implementation(libs.okhttp)
    // Gson：**不是给我们的业务代码用的**，是真实 csp jar 的外部依赖。
    // 反汇编 7 个真实 jar 后确认 com/google/gson 被 7/7 引用，且没有一个把 Gson
    // 打进 jar，说明这一层必须由宿主提供。业务代码仍然只用 org.json，别在这里加戏。
    implementation(libs.gson)
    // 本地代理服务的 HTTP 层。jar 把播放地址发成 http://127.0.0.1:<port>/proxy?…，
    // 播放器来取的那一刻，宿主必须真的在那儿听着，并把请求回灌给 jar 自己的
    // 静态 com.github.catvod.spider.Proxy.proxy(Map)。
    implementation(libs.nanohttpd)

    // `.js` 爬虫（drpy 系）的 JS 引擎。两个 artifact 都要，原因见 libs.versions.toml。
    // ⚠️ 它带各 ABI 的 libquickjs.so，release 体积会明显变大（几个 MB 量级）。
    implementation(libs.quickjs.android)
    implementation(libs.quickjs.java)

    implementation(libs.androidx.compose.material.icons.extended)

    // 持久化：观看进度（续播）与收藏
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // 真实 org.json：android.jar 里的是桩，JVM 单测直接抛 "Stub!"
    testImplementation(libs.org.json)
    // 协程单测：runTest 的虚拟时间 + Dispatchers.setMain（播放页状态持有者用得上）
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
