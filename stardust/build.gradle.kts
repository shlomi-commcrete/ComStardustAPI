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


android {
    namespace = "com.commcrete.stardust"
    compileSdk = 34
    buildFeatures {
        buildConfig = true // ✅ Enable BuildConfig for library module
    }
    defaultConfig {
        minSdk = 21

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
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")

    val room_version = "2.6.1"

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

    //Exo player
    implementation ("com.google.android.exoplayer:exoplayer:2.19.1")
    //easy permission
    implementation ("pub.devrel:easypermissions:3.0.0")

    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    implementation ("com.github.mik3y:usb-serial-for-android:3.8.0")

    implementation ("androidx.lifecycle:lifecycle-livedata-ktx:2.8.3")

    implementation ("com.github.shlomi-commcrete:TestNewLib4:0.0.14")

    implementation ("androidx.compose.material:material-icons-core:1.6.3")
    implementation ("androidx.compose.material:material-icons-extended:1.6.3")

    //Excel
    implementation ("org.apache.poi:poi-ooxml:5.2.3")
//    implementation ("org.apache.commons:commons-compress:1.21")
    implementation ("androidx.security:security-crypto:1.0.0")
}


afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.commcrete.stardust"
                artifactId = "stardust"
                version = "0.0.7"
            }
        }
    }
}