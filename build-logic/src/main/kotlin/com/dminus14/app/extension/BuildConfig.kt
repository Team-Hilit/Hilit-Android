package com.dminus14.app.extension

import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Convention Plugin이 공유하는 저장소 전역 빌드 설정값이다.
 *
 * SDK, JVM target, 애플리케이션 버전의 단일 기준점으로 사용하며 모듈별 설정은 이 값을
 * 직접 복제하지 않는다.
 */
internal object BuildConfig {
    /** Android 모듈과 KMP Android target이 사용하는 compile SDK 버전이다. */
    const val ANDROID_COMPILE_SDK = 37

    /** 모든 Android target이 지원하는 최소 SDK 버전이다. */
    const val ANDROID_MIN_SDK = 30

    /** `:app`이 대상으로 하는 Android SDK 버전이다. */
    const val ANDROID_TARGET_SDK = 36

    /** 일반 JVM, Android와 KMP Android target이 공유하는 JVM 버전이다. */
    const val JVM_VERSION = 17

    /** Java compile option에 사용하는 JVM 버전 표현이다. */
    val JAVA_VERSION = JavaVersion.toVersion(JVM_VERSION)

    /** Kotlin compiler option에 사용하는 JVM target 표현이다. */
    val KOTLIN_JVM_TARGET = JvmTarget.fromTarget(JVM_VERSION.toString())

    /** `:app` 배포 산출물의 version code다. */
    const val APPLICATION_VERSION_CODE = 16

    /** `:app` 배포 산출물의 version name이다. */
    const val APPLICATION_VERSION_NAME = "1.0.0-16"
}
