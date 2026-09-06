import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

configurations.configureEach {
    resolutionStrategy {
        cacheChangingModulesFor(192, TimeUnit.HOURS)
    }
}

android {
    namespace = "ali.paf.contacts"
    compileSdk = 34

    defaultConfig {
        applicationId = "ali.paf.contacts"
        minSdk = 24
        targetSdk = 34
        versionCode = 100200
        versionName = "1.0.20"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionNameSuffix = "PAF"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.material)
    implementation(libs.guava.listenablefuture)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coroutines.android)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.dav4jvm) {
        exclude(group = "org.ogce", module = "xpp3")
    }
    implementation(libs.vcard4android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
