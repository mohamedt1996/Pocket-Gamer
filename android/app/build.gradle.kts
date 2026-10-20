plugins { id("com.android.application") }
android {
 namespace = "org.pocketarcade.madeira"
 compileSdk = 35
 ndkVersion = "27.3.13750724"
 defaultConfig {
  applicationId = "org.pocketarcade.madeira"
  minSdk = 28
  targetSdk = 28 // Prototype only: review modern Android policies before distribution.
  versionCode = 2
  versionName = "0.2.0-port-candidate"
  ndk { abiFilters += "arm64-v8a" }
  externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
 }
 externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
