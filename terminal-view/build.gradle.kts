plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.termux.view"
  compileSdk = 35

  defaultConfig {
    minSdk = 26
    consumerProguardFiles("consumer-rules.pro")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  api(project(":terminal-emulator"))
  implementation(libs.androidx.annotation)
  testImplementation(libs.junit)
}
