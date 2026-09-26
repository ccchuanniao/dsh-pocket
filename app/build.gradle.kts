import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名材料不进版本库，和它解锁的 keystore 放在一起（keystore.properties，见 .gitignore）。
// 没有这个文件时构建仍然可用，只是产出未签名的包。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * 各人自己的部署参数，不进版本库。
 *
 * 复制根目录的 `dsh-pocket.properties.example` 为 `dsh-pocket.properties` 再填自己的值。
 * 优先级：命令行 `-Pkey=value` > 该文件 > 代码里的中性默认值。
 * 文件不存在也能构建 —— 默认值下推送和链接唤起不会生效，其余功能正常。
 */
val deployment = Properties().apply {
    val file = rootProject.file("dsh-pocket.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun deploymentValue(key: String, fallback: String): String =
    (project.findProperty(key) as String?)?.takeIf { it.isNotBlank() }
        ?: deployment.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: fallback

android {
    // namespace 只是源码里的包名，不出现在设备上，所以固定成中性值即可。
    namespace = "app.dshpocket"
    compileSdk = 36

    defaultConfig {
        // applicationId 是应用在设备上的身份，**也是 FCM 应用的注册身份** ——
        // 换一个就等于换一个应用（装不进旧的、收不到旧的推送）。所以它必须可覆盖：
        // 默认值给没有既有部署的人用，已经注册过 Firebase 的人用自己的值覆盖。
        applicationId = deploymentValue("applicationId", "app.dshpocket")
        minSdk = 26
        targetSdk = 36

        // App Links 的域名只能是构建者自己的：它要能被 android:autoVerify 验证，
        // 验证方式是访问该域名下的 /.well-known/assetlinks.json。默认值是个占位符，
        // 用默认值构建出来的包不会被任何真实域名唤起。
        manifestPlaceholders["appLinkHost"] = deploymentValue("appLinkHost", "harness.example.com")

        // 构建时注入的部署参数，由「DSH Pocket 配对」插件触发构建时传入（-Pxxx=...）。
        //
        // 为什么走构建期注入而不是配置文件：这样仓库里不放任何人的地址和 Firebase 配置，
        // 插件才能给别人用 —— 谁用谁填自己的。留空则对应功能不启用（地址留空就退回
        // "让用户自己填"的老流程；Firebase 留空则不启用推送）。
        //
        // 注意这里都是**客户端**配置：服务器地址、Firebase 的 projectId/appId/apiKey/senderId。
        // 这些值在每个 Firebase App 里都有，不是密钥；真正的密钥是服务账号，只在服务端。
        // 走 deploymentValue 而不是 project.findProperty：这些键既要在插件触发构建时通过
        // `-Pxxx=...` 传进来，也要能被本地的 dsh-pocket.properties 填上。只读命令行的话，
        // 照着 example 文件填完再构建，值会静默为空。
        fun injected(property: String) = deploymentValue(property, "")
        fun field(name: String, value: String) =
            buildConfigField("String", name, "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        field("PAIR_BASE", injected("pairBase"))
        field("PAIR_KEY", injected("pairKey"))
        field("FIREBASE_PROJECT_ID", injected("firebaseProjectId"))
        field("FIREBASE_APP_ID", injected("firebaseAppId"))
        field("FIREBASE_API_KEY", injected("firebaseApiKey"))
        field("FIREBASE_SENDER_ID", injected("firebaseSenderId"))
        field("NOTIFY_TOPIC", injected("notifyTopic"))
        versionCode = 38
        versionName = "1.0.37"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("stable") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Signed with the same stable key as release, so an installed build can always be
            // upgraded in place. Never regenerate the keystore: an installed device could not
            // install an update afterwards.
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("stable")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // The WebView debug bridge is enabled from BuildConfig.DEBUG in debug builds.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // Firebase 只用来收推送。刻意不引 google-services 插件：客户端配置由构建时注入的
    // 4 个值在运行时组装（见 MainActivity 的 FirebaseOptions），这样仓库里不放任何人的
    // Firebase 配置，插件才能通用 —— 谁用谁填自己的。
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.webkit:webkit:1.14.0")
}
