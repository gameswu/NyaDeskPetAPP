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

// ==================== iOS Live2D 静态库自动编译 ====================
// 将 Live2DBridge.cpp + stb_impl_ios.c 编译为 libLive2DBridge.a，
// 按 iOS target 架构分别输出到对应 lib/ 目录。
// 这些 Task 会作为 cinterop 的前置依赖自动执行。

data class IosBuildTarget(
    val name: String,          // Gradle target name (iosArm64, iosX64, iosSimulatorArm64)
    val sdk: String,           // iphoneos / iphonesimulator
    val clangTarget: String,   // arm64-apple-ios15.0 etc.
    val libDir: String         // output directory under live2d/lib/
)

val iosBuildTargets = listOf(
    IosBuildTarget("iosArm64",          "iphoneos",        "arm64-apple-ios15.0",              "lib/ios-arm64"),
    IosBuildTarget("iosX64",            "iphonesimulator", "x86_64-apple-ios15.0-simulator",   "lib/ios-x64"),
    IosBuildTarget("iosSimulatorArm64", "iphonesimulator", "arm64-apple-ios15.0-simulator",    "lib/ios-simulator-arm64"),
)

val live2dSrcDir  = project.file("src/nativeInterop/cinterop/live2d")
val live2dIncDir  = project.file("src/nativeInterop/cinterop/live2d/include")
val cppSource     = live2dSrcDir.resolve("live2DBridge.cpp")
val cSource       = live2dSrcDir.resolve("stb_impl_ios.c")

// 为每个 iOS target 注册编译 Task
val buildLive2dBridgeTasks = iosBuildTargets.associate { target ->
    val taskName = "buildLive2dBridge_${target.name}"
    val outputLibPath = live2dSrcDir.resolve("${target.libDir}/libLive2DBridge.a").absolutePath
    val buildDirPath  = layout.buildDirectory.dir("live2dBridge/${target.name}").get().asFile.absolutePath
    val incDirPath    = live2dIncDir.absolutePath
    val cppSourcePath = cppSource.absolutePath
    val cSourcePath   = cSource.absolutePath
    // 提取纯字符串，避免 configuration cache 序列化问题
    val sdkName       = target.sdk
    val clangTgt      = target.clangTarget
    val targetLabel   = target.name

    target.name to tasks.register(taskName) {
        group = "live2d"
        description = "Compile Live2DBridge static library for $targetLabel"

        inputs.files(cppSourcePath, cSourcePath)
        inputs.dir(incDirPath)
        outputs.file(outputLibPath)

        onlyIf {
            // 检查 Xcode SDK 是否可用（没有完整 Xcode 时跳过）
            val sdkCheck = try {
                val p = ProcessBuilder("xcrun", "--sdk", sdkName, "--show-sdk-path")
                    .redirectErrorStream(true).start()
                p.waitFor() == 0
            } catch (_: Exception) { false }

            if (!sdkCheck) {
                logger.warn("⚠️ iOS SDK '$sdkName' not found — skipping $targetLabel bridge build. Install Xcode to enable.")
                false
            } else {
                val outFile = File(outputLibPath)
                val cppFile = File(cppSourcePath)
                val cFile   = File(cSourcePath)
                !outFile.exists() || cppFile.lastModified() > outFile.lastModified()
                        || cFile.lastModified() > outFile.lastModified()
            }
        }

        doLast {
            File(buildDirPath).mkdirs()
            File(outputLibPath).parentFile.mkdirs()

            fun runCmd(vararg args: String) {
                val proc = ProcessBuilder(args.toList())
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                if (code != 0) {
                    throw GradleException("Command failed (exit $code): ${args.joinToString(" ")}\n$output")
                }
            }

            // 编译 Live2DBridge.cpp
            runCmd(
                "xcrun", "-sdk", sdkName, "clang++",
                "-std=c++17", "-c", "-O2",
                "-target", clangTgt,
                "-I$incDirPath",
                "-o", "$buildDirPath/Live2DBridge.o",
                cppSourcePath
            )
            // 编译 stb_impl_ios.c
            runCmd(
                "xcrun", "-sdk", sdkName, "clang",
                "-c", "-O2",
                "-target", clangTgt,
                "-I$incDirPath",
                "-o", "$buildDirPath/stb_impl_ios.o",
                cSourcePath
            )
            // 打包为静态库
            runCmd(
                "ar", "rcs", outputLibPath,
                "$buildDirPath/Live2DBridge.o",
                "$buildDirPath/stb_impl_ios.o"
            )
            logger.lifecycle("✅ Built libLive2DBridge.a for $targetLabel")
        }
    }
}

kotlin {
    // Suppress "expect/actual classes are in Beta" warnings project-wide
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

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
        val libDir = iosBuildTargets.first { it.name == iosTarget.name }.libDir
        val libPath = project.file("src/nativeInterop/cinterop/live2d/$libDir").absolutePath

        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            binaryOption("bundleId", "com.gameswu.nyadeskpet")
            // Xcode 26+ splits SwiftUICore into a restricted private framework;
            // use the classic linker to avoid "not an allowed client" TBD errors.
            linkerOpts("-ld64")
        }

        // Live2D cinterop — bind C bridge API + Cubism Core static library
        iosTarget.compilations.getByName("main") {
            cinterops {
                val live2d by creating {
                    defFile(project.file("src/nativeInterop/cinterop/live2d.def"))
                    includeDirs(project.file("src/nativeInterop/cinterop/live2d/include"))

                    // Per-target library paths
                    val libDir = iosBuildTargets.first { it.name == iosTarget.name }.libDir
                    extraOpts("-libraryPath", project.file("src/nativeInterop/cinterop/live2d/$libDir").absolutePath)
                }
            }

            // 让 cinterop Task 依赖静态库编译 Task
            val buildTask = buildLive2dBridgeTasks[iosTarget.name]
            if (buildTask != null) {
                tasks.matching { it.name.startsWith("cinteropLive2d${iosTarget.name.replaceFirstChar { c -> c.uppercase() }}") }
                    .configureEach { dependsOn(buildTask) }
            }
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
