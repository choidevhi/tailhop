import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.choidev.tailhop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.choidev.tailhop"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file("keystore/" + keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    // 앱이 지원하는 언어만 남긴다(라이브러리의 다른 언어 문자열이 섞여 일부만 번역돼 보이지 않게, APK도 작게).
    androidResources { localeFilters += listOf("en", "ko", "ja", "zh-rCN") }

    lint {
        // 번역 누락은 릴리즈 빌드를 막는다(assembleRelease의 lintVital도 검사).
        fatal += setOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")
        // android:tint는 minSdk 26의 ImageView에서 그대로 동작한다(AppCompat의 app:tint로 바꿀 이유 없음).
        disable += "UseAppTint"
        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.systemProperty("tailhop.vectors", rootProject.file("../docs/test_vectors.json").absolutePath)
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
