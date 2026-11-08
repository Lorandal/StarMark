import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val starMarkTimestamp = providers.gradleProperty("starmarkBuildTimestamp").getOrElse(
    ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
)
val channelConfig = Properties().apply {
    rootProject.file("../version.properties").reader(Charsets.UTF_8).use { load(it) }
}
val starMarkChannel = channelConfig.getProperty("channel")
val starMarkBase = channelConfig.getProperty("baseVersion")
val starMarkIteration = channelConfig.getProperty("iteration").toInt()
val starMarkVersionCode = channelConfig.getProperty("versionCode").toInt()
val starMarkSourceHash = providers.gradleProperty("starmarkSourceSha256").getOrElse("")
require(starMarkChannel in listOf("Dev", "Beta", "Release")) { "Invalid channel" }
require(starMarkBase.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) && starMarkBase.substringAfterLast('.').toInt() <= 999)
require(starMarkVersionCode > 0)
require(when (starMarkChannel) {
    "Dev" -> starMarkIteration in 0..49 || starMarkIteration in 100..499
    "Beta" -> starMarkIteration in 50..99 || starMarkIteration in 500..999
    else -> starMarkBase.substringBefore('.').toInt() >= 1 && starMarkIteration == 0
}) { "Version iteration outside the channel range" }
require(starMarkChannel == "Dev" || starMarkSourceHash.matches(Regex("[0-9a-f]{64}"))) { "Beta/Release require a source snapshot: use scripts/build-delivery.ps1" }
val starMarkDate = starMarkTimestamp.substringBefore(' ').replace("/", "")
val starMarkVersionName = "$starMarkChannel.$starMarkBase.${starMarkIteration}_${if (starMarkChannel == "Dev") starMarkDate else starMarkSourceHash.take(7).uppercase()}"

android {
    namespace = "dev.starmark.starmark"
    compileSdkVersion("android-37.0")
    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = if (starMarkChannel == "Dev") "dev.realize.starmark" else "com.realize.starmark"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = starMarkVersionCode
        versionName = starMarkVersionName
        buildConfigField("String", "BUILD_CHANNEL", "\"$starMarkChannel\"")
        buildConfigField("String", "SOURCE_SHA256", "\"$starMarkSourceHash\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$starMarkTimestamp\"")
        externalNativeBuild {
            cmake { targets += "starmark_jpeg" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    signingConfigs {
        getByName("debug") { enableV1Signing = true; enableV2Signing = true; enableV3Signing = true }
        if (starMarkChannel != "Dev") {
            val configPath = providers.environmentVariable("STARMARK_SIGNING_PROPERTIES").orNull
                ?: error("Set STARMARK_SIGNING_PROPERTIES for distribution builds")
            val credentialsFile = file(configPath)
            require(credentialsFile.isFile) { "Distribution signing credentials are missing" }
            val credentials = Properties().apply { credentialsFile.reader(Charsets.UTF_8).use { load(it) } }
            create("distribution") {
                storeFile = credentialsFile.parentFile.resolve(credentials.getProperty("storeFile"))
                storePassword = credentials.getProperty("storePassword")
                keyAlias = credentials.getProperty("keyAlias")
                keyPassword = credentials.getProperty("keyPassword")
                enableV1Signing = true; enableV2Signing = true; enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Channel, not optimization mode, selects the signing identity.
            signingConfig = signingConfigs.getByName(if (starMarkChannel == "Dev") "debug" else "distribution")
        }
        debug { signingConfig = signingConfigs.getByName(if (starMarkChannel == "Dev") "debug" else "distribution") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

// Replace Miuix's single-cubic/SDF corners with the API-compatible G2 implementation.
configurations.configureEach {
    exclude(group = "top.yukonga.miuix.kmp", module = "miuix-squircle")
    exclude(group = "top.yukonga.miuix.kmp", module = "miuix-squircle-android")
}

dependencies {
    implementation("dev.chrisbanes.haze:haze-glass-android:2.1.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.savedstate:savedstate:1.4.0")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    // Keep debug/runtime and androidTest consistent with Flutter's older test plugin.
    debugImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    testImplementation("junit:junit:4.13.2")
}
