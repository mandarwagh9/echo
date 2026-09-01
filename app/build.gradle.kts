// Imported rather than fully qualified: inside the android {} blocks, Gradle's
// own `java` extension shadows the java.* package, so `java.util.Properties`
// fails to resolve.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// ---------------------------------------------------------------------------
// Who is this APK for?
//
//   personal (default) -- this machine's build. `local.properties` supplies the
//     STT/upload endpoints and their shared secrets, and the APK is signed with
//     the Android debug key so it installs over the existing one.
//
//   public -- an APK handed to somebody else:
//     ./gradlew :app:assembleRelease -PechoDistribution=public -PechoAbi=arm64-v8a
//
// The two differ in exactly the way that matters: a public APK compiles all four
// BuildConfig secrets to the empty string. They cannot simply be omitted from
// local.properties instead, because EchoSettings *falls back* to them whenever
// its DataStore key is absent -- so a public build carrying them would silently
// point every stranger's phone at the maintainer's paid GCP project, with the
// credentials recoverable from the APK by `strings`.
//
// Public builds must also be signed with the real upload key, which is what
// makes the guarantee structural rather than a convention: the debug-signed
// artifact and the secret-free artifact cannot be the same file, and the build
// refuses to produce a public one without the keystore.
// ---------------------------------------------------------------------------
val distribution = (project.findProperty("echoDistribution") as String?) ?: "personal"
require(distribution in setOf("personal", "public")) {
    "echoDistribution must be 'personal' or 'public', was '$distribution'"
}
val isPublicBuild = distribution == "public"

val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let {
    rootProject.file(it).exists()
} ?: false

if (isPublicBuild && !hasReleaseKey) {
    throw GradleException(
        "A public build must be signed with the upload key, and keystore.properties is " +
            "missing or names a keystore that is not there. " +
            "Run: powershell -File scripts/make-release-key.ps1",
    )
}

android {
    namespace = "com.mandar.echo"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.mandar.echo"
        minSdk = 29
        targetSdk = 36
        // Public beta. versionCode must increase on every artifact handed out or
        // Android refuses to install the update over the old one; versionName is
        // what a tester quotes back in a bug report.
        versionCode = 2
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default transcription server, read from local.properties so the shared
        // secret stays on this machine -- that file is gitignored. Absent values
        // compile to empty strings, and Echo then simply stays on-device.
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }
                ?.inputStream()?.use { load(it) }
        }
        // A public build reads none of them -- not "reads them and hides them":
        // the constants are not in the APK at all.
        fun secret(key: String): String =
            if (isPublicBuild) "" else localProps.getProperty(key, "")

        buildConfigField("String", "STT_URL", "\"${secret("echo.stt.url")}\"")
        buildConfigField("String", "STT_KEY", "\"${secret("echo.stt.key")}\"")
        // The batch pipeline's signed-URL minting service. Same rule: absent
        // values compile to empty strings and the backend simply cannot be used.
        buildConfigField("String", "UPLOAD_URL", "\"${secret("echo.upload.url")}\"")
        buildConfigField("String", "UPLOAD_KEY", "\"${secret("echo.upload.key")}\"")

        // Lets the UI tell the two apart: a public build offers to *configure* a
        // server, a personal one already has one.
        buildConfigField("boolean", "PUBLIC_BUILD", "$isPublicBuild")

        ndk {
            // arm64-v8a is the only ABI a real phone needs. x86_64 is here purely
            // so the pipeline can be verified end-to-end on an emulator, and it
            // roughly doubles the native payload.
            //
            //   ./gradlew :app:assembleRelease -PechoAbi=arm64-v8a
            //
            // builds the phone-only APK, which is what you want for sideloading.
            val requested = (project.findProperty("echoAbi") as String?)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
            abiFilters += requested ?: listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += "-O3"
            }
        }
    }

    signingConfigs {
        // A personal build is signed with the debug key on purpose: it lets
        // `assembleRelease` produce a directly installable APK with -O3 native
        // code, on top of whatever is already on the developer's phone.
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Anything handed to another person. Android identifies an app by its
        // signing key for life, so this keystore is the only thing that can ever
        // ship an update to an installed Echo -- lose it and every tester has to
        // uninstall, taking their transcripts with them.
        if (hasReleaseKey) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (isPublicBuild) "upload" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Native code still needs optimisation or whisper cannot keep up with
            // realtime. Without this, a debug build compiles ggml at -O0.
            externalNativeBuild {
                cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Instrumented tests run against the release variant so they exercise the same
    // -O3 native build that ships, and so they do not trigger a second full NDK
    // compile of ggml for the debug variant.
    testBuildType = "release"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = false }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * Export the Room schema as JSON, committed under `app/schemas/`.
 *
 * `fallbackToDestructiveMigration` is deliberately absent, so a wrong migration
 * does not quietly wipe a database -- it crashes the app, permanently, on a
 * stranger's phone with their recordings inside it. Until now there was no
 * golden schema to diff a new version against and `MIGRATION_2_3` would have had
 * to be written by eye. With the export on, `MigrationTestHelper` can open a real
 * v2 database and run the migration against it.
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    // No material-icons-extended. It bundles ~1,500 vector icons as generated
    // Kotlin, which cost about 30 MB of dex -- two thirds of the APK -- and this
    // app draws every glyph it uses itself in ui/components/Primitives.kt.

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:$room")
    // org.json is an Android platform class, so under `isReturnDefaultValues = true`
    // it resolves to the mockable android.jar where every method returns a default:
    // JSONObject("...").has(k) would be false and optString(k) would be "" for every
    // input, and the BatchProtocol table below would pass without parsing anything.
    // The real implementation is ~70 KB with no dependencies and wins on classpath
    // order; JsonRealityCheckTest asserts that it did, because a silent regression
    // here makes the whole suite vacuous.
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
