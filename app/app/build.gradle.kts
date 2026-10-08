import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.chaquopy)
  alias(libs.plugins.compose.compiler)
  id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

detekt {
  buildUponDefaultConfig = true // preconfigure defaults
  allRules = false // activate all available (even unstable) rules.
}

tasks.withType<Detekt>().configureEach {
  reports {
    html.required.set(true) // observe findings in your browser with structure and code snippets
  }
}

android {
  namespace = "com.wboelens.polarrecorder"
  compileSdk = 36

  defaultConfig {
    applicationId = "it.biosleep.recorder"
    minSdk = 26
    targetSdk = 36
    versionCode = 26
    versionName = "2.1.1"

    // Nome dell'app: UNICA fonte. Genera @string/app_name e BuildConfig.APP_NAME.
    val nomeApp = "NoctaliX"
    resValue("string", "app_name", nomeApp)
    buildConfigField("String", "APP_NAME", "\"$nomeApp\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Collegamento OAuth a Intervals.icu: client_id e indirizzi non sono segreti (il client_secret
    // lo conosce solo il Worker). OAUTH_HOST = dominio dell'App Link verificato (assetlinks.json).
    buildConfigField("String", "INTERVALS_CLIENT_ID", "\"1235\"")
    buildConfigField("String", "OAUTH_HOST", "\"biosleep-oauth.simonegerardini.workers.dev\"")
    buildConfigField("String", "OAUTH_REDIRECT_URI", "\"https://biosleep-oauth.simonegerardini.workers.dev/callback\"")
    ndk {
      // Solo telefoni a 64 bit ARM: ogni architettura in piu' porta un'altra copia di Python.
      // Per l'emulatore del Mac aggiungere "x86_64" (o "arm64-v8a" basta sui Mac con chip Apple).
      abiFilters += listOf("arm64-v8a")
    }
  }

  buildTypes {
    debug { isDebuggable = true }
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      ndk { debugSymbolLevel = "SYMBOL_TABLE" }
    }
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true // BuildConfig.INTERVALS_CLIENT_ID e indirizzi OAuth
    resValues = true // @string/app_name generato da nomeApp (nome unico dell'app)
  }

  testOptions { unitTests.all { it.useJUnitPlatform() } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

tasks.withType<Test> {
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    showStandardStreams = true
  }
}

dependencies {
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core.testing)
  testImplementation(libs.mockk)
  testImplementation(libs.turbine)
  testImplementation(libs.kotlinx.coroutines.test)
  coreLibraryDesugaring(libs.android.desugar)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.browser) // Custom Tab per il collegamento a Intervals.icu

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.documentfile)
  implementation(libs.material)
  implementation(libs.androidx.activity)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.navigation.compose)
  testImplementation(libs.junit)
  testImplementation(libs.junit5.api)
  testImplementation(libs.junit5.params)
  testRuntimeOnly(libs.junit5.engine)
  testRuntimeOnly(libs.junit.vintage.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)

  implementation(libs.polar.ble.sdk)
  implementation(libs.rxjava)
  implementation(libs.rxandroid)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.rx3)
  implementation(libs.androidx.activity.ktx)
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.play.services.location)

  implementation(libs.androidx.material3)
  implementation(libs.androidx.material3.windowsizeclass)
  implementation(libs.androidx.material3.adaptive.navigation.suite)
  implementation(libs.androidx.compose.material.iconsExtended)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.runtime.livedata)

  implementation(libs.gson)
}

chaquopy {
  defaultConfig {
    version = "3.12"
    // Python del Mac usato in compilazione (stessa versione 3.12). Togli il commento e
    // correggi il percorso solo se la build dice che non lo trova:
    // buildPython("/opt/homebrew/bin/python3.12")
    pip {
      install("requests")
      install("tzdata") // fusi orari per zoneinfo: Android non li fornisce a Python
    }
  }
}
