import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.library")
  alias(libs.plugins.kotlin.compose)
}

val commonMainKotlinFiles = fileTree("src/commonMain/kotlin") {
  include("**/*.kt")
}.files.sortedBy { it.absolutePath }

android {
  namespace = "dev.mttmcknn.seaglass"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  sourceSets.named("main") {
    kotlin.directories += listOf("src/commonMain/kotlin", "src/androidMain/kotlin")
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    freeCompilerArgs.addAll(
      "-Xmulti-platform",
      "-Xexpect-actual-classes",
      "-Xcommon-sources=${commonMainKotlinFiles.joinToString(",") { it.absolutePath }}",
    )
  }
}

dependencies {
  api(platform(libs.androidx.compose.bom))
  api(libs.androidx.ui)
  api("androidx.compose.foundation:foundation")
  api(libs.androidx.material3)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
