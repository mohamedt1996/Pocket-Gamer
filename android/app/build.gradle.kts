plugins { id("com.android.application") }
// -PwineVariant=madeira builds the experimental APK around Madeira's Wine. It gets
// its own application ID and name so it installs next to the regular app.
val wineVariant = (findProperty("wineVariant") as String?) ?: "proton"
require(wineVariant == "proton" || wineVariant == "madeira") { "wineVariant must be proton or madeira" }
android {
 namespace = "org.pocketarcade.madeira"
 compileSdk = 35
 ndkVersion = "27.3.13750724"
 defaultConfig {
  applicationId = if (wineVariant == "madeira") "org.pocketarcade.madeira.winemadeira" else "org.pocketarcade.madeira"
  manifestPlaceholders["appLabel"] = if (wineVariant == "madeira") "Pocket Arcade (Madeira Wine)" else "Pocket Arcade"
  minSdk = 28
  targetSdk = 28 // Prototype only: review modern Android policies before distribution.
  versionCode = 2
  versionName = "0.2.0-port-candidate" + (if (wineVariant == "madeira") "-madeira-wine" else "")
  ndk { abiFilters += "arm64-v8a" }
  externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
 }
 externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
