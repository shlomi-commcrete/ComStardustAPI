plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    id("kotlin-kapt")
    id("maven-publish")
}

// ✅ Load API key (prefer from ~/.gradle/gradle.properties, fallback to environment var)
val apiKeyProvider = providers.gradleProperty("Crypt")
    .orElse(providers.environmentVariable("Crypt"))

val apiKey = apiKeyProvider.orElse("").get()

// SecureKeyUtils.getSecuredKey() feeds BuildConfig.Crypt straight into hexStringToByteArray(), and
// SecureKeyStore.setKey() requires exactly 32 bytes. An unset or malformed value silently yields a
// zero-length key at runtime and makes setSecuredKeyDefault() throw IllegalArgumentException, a long
// way from the build that caused it. Non-hex characters are worse: Character.digit() returns -1 and
// the key becomes garbage without any error at all. Fail here instead.
require(apiKey.matches(Regex("[0-9a-fA-F]{64}"))) {
    val problem = if (apiKey.isEmpty()) "it is not set" else "got ${apiKey.length} character(s)"
    "Gradle property 'Crypt' must be exactly 64 hex characters (a 32-byte key) - $problem. " +
        "Set it in gradle.properties, ~/.gradle/gradle.properties, or the Crypt environment variable."
}


android {
    namespace = "com.commcrete.stardust"
    compileSdk = 36
    buildFeatures {
        buildConfig = true // ✅ Enable BuildConfig for library module
    }
    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "Crypt", "\"$apiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
//            proguardFiles(
//                getDefaultProguardFile("proguard-android-optimize.txt"),
//                "proguard-rules.pro"
//            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Required by AGP 8.x for the maven-publish "release" publication below.
    publishing {
        singleVariant("release") {}
    }

}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.media3:media3-common:1.4.0")
    implementation("androidx.navigation:navigation-runtime-ktx:2.7.7")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")


    //BLE
    implementation("no.nordicsemi.android:ble:2.7.4")
    implementation("no.nordicsemi.android:ble-common:2.7.4")

    //Location
    implementation("com.google.android.gms:play-services-location:21.0.1")

    //Logging
    implementation ("com.jakewharton.timber:timber:5.0.1")

    //Data
    // Gson directly rather than via retrofit2:converter-gson - Retrofit is not used anywhere in this
    // library, and pulling it in dragged okhttp 3.14.9 + okio 1.17.2 onto the consumer's classpath
    // (ATAK core supplies okhttp 4.11.0 / okio 3.2.0, and okio 3 dropped the okio.Okio class that
    // okhttp 3.x calls into) as well as pinning gson to 2.8.5.
    implementation ("com.google.code.gson:gson:2.11.0")

    // Room 2.7+ is required on Kotlin 2.x: room-compiler 2.6.1 bundles a kotlinx-metadata-jvm that
    // rejects Kotlin 2.2 metadata ("maximum supported version is 2.0.0") and fails kapt.
    val room_version = "2.7.2"

    implementation("androidx.room:room-runtime:$room_version")
    annotationProcessor("androidx.room:room-compiler:$room_version")

    // To use Kotlin annotation processing tool (kapt)
    kapt("androidx.room:room-compiler:$room_version")
    // optional - Kotlin Extensions and Coroutines support for Room
    implementation("androidx.room:room-ktx:$room_version")

    // optional - RxJava2 support for Room
    implementation("androidx.room:room-rxjava2:$room_version")

    // optional - RxJava3 support for Room
    implementation("androidx.room:room-rxjava3:$room_version")

    // optional - Guava support for Room, including Optional and ListenableFuture
    implementation("androidx.room:room-guava:$room_version")

    // optional - Test helpers
    testImplementation("androidx.room:room-testing:$room_version")

    // optional - Paging 3 Integration
    implementation("androidx.room:room-paging:$room_version")

    // Media playback is androidx.media3 (see PlayerUtils) - the end-of-life ExoPlayer 2.19.1
    // artifact that used to sit here had no imports anywhere in the library. It did, however, pull
    // androidx.media in transitively via exoplayer-ui, which is where USBAudioReceiver's
    // MediaSessionCompat comes from - so that one is now declared explicitly.
    implementation ("androidx.media:media:1.7.0")
    //easy permission
    implementation ("pub.devrel:easypermissions:3.0.0")

    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    // Pinned to the version ATAK 5.6 core bundles (docs/dependencies.txt).  ATAK loads
    // plugin classes parent-first, so core's copy of com.hoho.android.usbserial always
    // wins at runtime - compiling against anything else means compile-time and runtime
    // APIs can disagree.  3.8.0 vs core's 3.9.0 is exactly how the
    // "SerialInputOutputManager cannot be cast to java.lang.Runnable" crash happened:
    // 3.8.0 implements Runnable, 3.9.0 does not (use start()/stop() - see UARTManager).
    implementation ("com.github.mik3y:usb-serial-for-android:3.9.0")

    implementation ("androidx.lifecycle:lifecycle-livedata-ktx:2.8.3")

    implementation ("com.github.shlomi-commcrete:TestNewLib4:0.0.14")

    implementation ("androidx.compose.material:material-icons-core:1.6.3")
    implementation ("androidx.compose.material:material-icons-extended:1.6.3")

    //Excel
    implementation ("org.apache.poi:poi-ooxml:5.2.3")
//    implementation ("org.apache.commons:commons-compress:1.21")
    implementation ("androidx.security:security-crypto:1.0.0")

    implementation("org.pytorch:pytorch_android_lite:2.1.0")

    implementation("org.pytorch:pytorch_android_torchvision_lite:2.1.0")

//    implementation(files("libs/ATAK-Plugin-pytorch-mx-plugin-2.1-99a20191-5.0.0-civ-debug.aar"))

//
//    configurations.configureEach {
//        exclude(group = "org.pytorch", module = "pytorch_android")
//        exclude(group = "org.pytorch", module = "pytorch_android_torchvision")
//    }
}



afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.commcrete.stardust"
                artifactId = "stardust"
                version = "0.0.300"
            }
        }
    }
}
