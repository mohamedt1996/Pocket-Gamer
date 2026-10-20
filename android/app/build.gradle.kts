plugins { id("com.android.application") }
android {
 namespace = "org.pocketarcade.madeira"
 compileSdk = 35
 defaultConfig {
  applicationId = "org.pocketarcade.madeira"
  minSdk = 28
  targetSdk = 28 // Prototype only: review modern Android policies before distribution.
  versionCode = 1
  versionName = "0.1.0-dev"
  ndk { abiFilters += "arm64-v8a" }
  externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
 }
 externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
