import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val ks = Properties().apply { val f = rootProject.file("keystore/keystore.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
  namespace = "br.geracaoai.baixartg"
  compileSdk = 35
  defaultConfig { applicationId = "br.geracaoai.baixartg"; minSdk = 30; targetSdk = 35; versionCode = 11; versionName = "1.6.4"
    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }
  signingConfigs { create("release") { if (ks.isNotEmpty()) { storeFile = rootProject.file(ks["storeFile"] as String); storePassword = ks["storePassword"] as String; keyAlias = ks["keyAlias"] as String; keyPassword = ks["keyPassword"] as String } } }
  buildTypes { release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); signingConfig = signingConfigs.getByName("release") } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true; buildConfig = true }
  packaging { jniLibs { useLegacyPackaging = false } }
  lint { checkReleaseBuilds = false; abortOnError = false }
}
dependencies {
  val bom = platform("androidx.compose:compose-bom:2024.09.00")
  implementation(bom)
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("io.github.tdlib-android:core:0.1.1")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.jsoup:jsoup:1.18.1")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")
}
