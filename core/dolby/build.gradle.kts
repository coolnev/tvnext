plugins { id("com.android.library") }

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    api("androidx.media3:media3-decoder:1.11.0")
    implementation(libs.media3.exoplayer)
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.48.4")
}

// Host JNI is rebuilt from the same pristine FFmpeg/JNI sources for native decoder tests.
tasks.withType<Test>().configureEach {
    onlyIf("A host JNI build is configured for these native decoder tests") {providers.environmentVariable("TVNEXT_FFMPEG_HOST_LIBRARY").isPresent}
    providers.environmentVariable("TVNEXT_FFMPEG_HOST_LIBRARY").orNull?.let {systemProperty("java.library.path",it)}
}
