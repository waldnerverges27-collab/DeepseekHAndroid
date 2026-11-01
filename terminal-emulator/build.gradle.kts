plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.termux.terminal"
  compileSdk = 35
  ndkVersion = "27.2.12479018"

  defaultConfig {
    minSdk = 26
    consumerProguardFiles("consumer-rules.pro")

    externalNativeBuild {
      cmake {
        arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
      }
    }

    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

dependencies {
  implementation(libs.androidx.annotation)
  testImplementation(libs.junit)
}
