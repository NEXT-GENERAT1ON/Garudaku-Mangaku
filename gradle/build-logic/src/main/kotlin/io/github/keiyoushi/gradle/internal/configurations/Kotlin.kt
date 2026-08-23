package io.github.keiyoushi.gradle.internal.configurations

import io.github.keiyoushi.gradle.internal.extensions.kei
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import tapmoc.configureJavaCompatibility

internal fun Project.configureKotlin() {
    configureJavaCompatibility(kei.versions.java.get().toInt())

    kotlin {
