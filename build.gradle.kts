import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.deepseek.harness"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("intellijPlatformLocalPath").get())
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // 平台自带的是 Kotlin 标准库 2.2.20（实测 PyCharm 2025.3.3 的 lib/util-8.jar），插件产物里
        // 也不打包 stdlib，所以运行时用的就是 2.2.20。把语言/API 版本对齐到 2.2，编译器才会拦住
        // "编译期能过、平台运行时缺方法" 的调用。
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 与构建/验证所用的平台（本地 PyCharm 2025.3.3 = 253）保持一致：
            // 该平台自带 Kotlin stdlib 2.2.20，正好匹配上面的 apiVersion 2.2。
            // 不声明 untilBuild，后续版本可继续安装。
            sinceBuild = "253"
        }
    }
}
