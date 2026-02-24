import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// ==================== 版本号自动生成 — 对齐原项目 package.json + version.json 机制 ====================
// 从 gradle.properties 读取版本信息，构建时自动生成 AppBuildConfig.kt

val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildconfig/commonMain/kotlin")
    val versionName = providers.gradleProperty("app.versionName").getOrElse("0.0.0")
    val versionCode = providers.gradleProperty("app.versionCode").getOrElse("1")
    outputs.dir(outputDir)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)

    doLast {
        val dir = outputDir.get().asFile.resolve("com/gameswu/nyadeskpet")
        dir.mkdirs()
        dir.resolve("AppBuildConfig.kt").writeText(
            """
            |package com.gameswu.nyadeskpet
            |
            |/**
            | * 构建时自动生成的版本信息 — 对齐原项目 package.json 版本管理
            | *
            | * ⚠️ 请勿手动编辑此文件！
            | * 修改版本请编辑 gradle.properties 中的 app.versionName 和 app.versionCode
            | */
            |object AppBuildConfig {
            |    const val VERSION_NAME = "${versionName}"
            |    const val VERSION_CODE = ${versionCode}
            |}
            """.trimMargin() + "\n"
        )
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            // 注册构建时生成的 AppBuildConfig.kt
            kotlin.srcDir(generateBuildConfig.map { it.outputs.files.singleFile })
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(compose.materialIconsExtended)

            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.navigation.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.multiplatform.markdown)
            implementation(libs.multiplatform.markdown.m3)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.android)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.activity.compose)
            
            // 💡 解决 NyaDeskPetApp.kt 和 AndroidModule.kt 报错的关键
            implementation("io.insert-koin:koin-android:4.0.2")
            implementation(libs.compose.uiTooling)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native)
        }
    }
}

android {
    namespace = "com.gameswu.nyadeskpet.library"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()

        externalNativeBuild {
            cmake {
                abiFilters.addAll(listOf("arm64-v8a", "x86_64", "x86"))
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.gameswu.nyadeskpet.data.db")
        }
    }
}
