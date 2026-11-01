import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "io.deepseek.harness.android"
  compileSdk = 35
  ndkVersion = "27.2.12479018"

  defaultConfig {
    applicationId = "io.deepseek.harness.android"
    minSdk = 26
    // targetSdk 34 garantiza compatibilidad completa en Android 10–15+:
    // proot y proot-loader se ejecutan desde nativeLibraryDir (jniLibs con
    // extractNativeLibs=true / useLegacyPackaging=true), cumpliendo SELinux W^X.
    targetSdk = 34

    versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 5
    versionName = (project.findProperty("versionName") as String?) ?: "1.0.4"

    val abiProp = (project.findProperty("abiFilters") as String?)
    ndk {
      if (!abiProp.isNullOrBlank()) {
        abiFilters += abiProp.split(",").map { it.trim() }.filter { it.isNotEmpty() }
      } else {
        abiFilters += listOf("arm64-v8a")
      }
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  androidResources {
    // Evita recomprimir rootfs.tar.xz y permite usar AssetManager.openFd()
    // para conocer el tamaño exacto durante la barra de progreso inicial.
    noCompress += listOf("xz", "gz", "tar")
  }

  packaging {
    resources {
      excludes += setOf(
        "META-INF/DEPENDENCIES",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
        "/META-INF/{AL2.0,LGPL2.1}",
      )
    }
    jniLibs {
      // Obliga a Android PackageManager a extraer libproot.so, libproot_loader.so,
      // libtalloc.so, libandroid-shmem.so y libtermux.so en nativeLibraryDir
      // con contexto SELinux apk_data_file ejecutable (sin root y compatible con 16KB pages).
      useLegacyPackaging = true
      // No ejecutar llvm-strip sobre proot ni su loader estático (sus segmentos PT_LOAD
      // personalizados se corromperían al hacer strip).
      keepDebugSymbols += listOf(
        "**/libproot.so",
        "**/libproot_loader.so",
        "**/libproot_loader32.so",
      )
    }
  }

  if (rootProject.file("release.keystore").exists()) {
    signingConfigs.create("releaseSign") {
      storeFile = rootProject.file("release.keystore")
      storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "android"
      keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "release"
      keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "android"
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    debug {
      isMinifyEnabled = false
    }
    release {
      isMinifyEnabled = false
      if (rootProject.file("release.keystore").exists()) {
        signingConfig = signingConfigs.getByName("releaseSign")
      }
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }

  lint {
    checkReleaseBuilds = false
    abortOnError = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

// Verificación previa al empaquetado de assets: exige rootfs.tar.xz real salvo
// compilaciones de humo en CI (-PallowMissingRootfs=true).
val allowMissingRootfs =
  (project.findProperty("allowMissingRootfs") as String?)?.toBoolean() ?: false

tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }.configureEach {
  doFirst {
    val rootfsAsset = file("src/main/assets/rootfs.tar.xz")
    if (!rootfsAsset.exists() && !allowMissingRootfs) {
      throw GradleException(
        """
        Falta el archivo app/src/main/assets/rootfs.tar.xz.
        Genera primero el rootfs autónomo (Debian 13/Ubuntu + última versión de Node.js + dsh) ejecutando:
          1) ./scripts/fetch-proot-binaries.sh
          2) ./scripts/build-rootfs-docker.sh   (o sudo ./scripts/build-rootfs.sh en host arm64)
        Para una compilación rápida de verificación sin rootfs completo usa:
          ./scripts/create-stub-rootfs.sh && ./gradlew assembleDebug
        """.trimIndent(),
      )
    }
  }
}

dependencies {
  implementation(project(":terminal-view"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.webkit)
  implementation(libs.kotlinx.coroutines.android)

  // Extracción en streaming de rootfs.tar.xz con soporte de enlaces simbólicos y duros
  implementation(libs.commons.compress)
  implementation(libs.tukaani.xz)

  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.kotlinx.coroutines.test)
}
