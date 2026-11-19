import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.pokemonivreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.pokemonivreader"
        minSdk = 28
        targetSdk = 37
        // Test build updated in place from signed 1.5.72 / 97; keep the existing app ID and signer.
        versionCode = 98
        versionName = "1.5.73"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Temporary stability fix: disable R8 until the 1.0.1 startup crash
            // is diagnosed with device Logcat. Keep the SAME application ID and signer.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Exercise the real history store with the same bundled catalog used on a device.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.32")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Robolectric downloads Android SDK test jars outside Gradle's dependency resolver.
// Explicitly use the canonical Maven Central host for test runtime downloads,
// rather than the repo1 default that failed with FileNotFoundException on CI.
// This does not bypass any test or change APK output.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}

// Download once at build time and bundle the fixed extractor for offline use.
// No training images or history are sent to the model distribution server.
abstract class PrepareVisualModel : DefaultTask() {
    @get:Input abstract val modelUrl: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val assetName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun prepare() {
        val output=outputDirectory.file(assetName.get()).get().asFile
        fun hash(file:File)=MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        if(output.isFile && hash(output)==sha256.get()) return
        output.parentFile.mkdirs()
        val temporary=File(output.parentFile,"model.download")
        try {
            val connection=URI(modelUrl.get()).toURL().openConnection().apply {
                connectTimeout=15000; readTimeout=45000
            }
            connection.getInputStream().use { input -> temporary.outputStream().use { target ->
                val buffer=ByteArray(8192);var total=0
                while(true) { val n=input.read(buffer);if(n<0)break;total+=n
                    check(total<=16*1024*1024) { "Visual model is too large" };target.write(buffer,0,n) }
            } }
            check(hash(temporary)==sha256.get()) { "Visual model checksum mismatch" }
            Files.move(temporary.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
val prepareVisualModel=tasks.register<PrepareVisualModel>("prepareVisualModel") {
    assetName.set("mobilenet_v3_small.tflite")
    modelUrl.set("https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite")
    sha256.set("bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40")
    outputDirectory.set(layout.buildDirectory.dir("generated/visualModelAssets"))
}
val catchModelBase = "https://media.githubusercontent.com/media/Juancavr6/RegiBot/f242ee151a5447e4cfe83db4ee9090459cc23503/app/app/src/main/assets"
val catchModels = mapOf(
    "model_classifier_screen_v5.tflite" to "6ef82b3185b39c1f777ac54db0a1498527c5d26f16eb575c2e21cb246172fa32",
    "model_detector_map_v2.tflite" to "a6d282845cd09bd4d09d611493b33d87a614174d3baeb2387309710d81b0a352",
    "model_detector_encounter.tflite" to "02ca77129978855dedb57dbaa01e59b8e48ddcaadff0b5a6944c1e68268633ae",
).map { (name, digest) ->
    tasks.register<PrepareVisualModel>("prepare_" + name.removeSuffix(".tflite")) {
        assetName.set(name)
        modelUrl.set("$catchModelBase/$name")
        sha256.set(digest)
        outputDirectory.set(layout.buildDirectory.dir("generated/catchModelAssets/" + name.removeSuffix(".tflite")))
    }
}
androidComponents.onVariants { variant ->
    (listOf(prepareVisualModel) + catchModels).forEach { task ->
        variant.sources.assets?.addGeneratedSourceDirectory(task, PrepareVisualModel::outputDirectory)
    }
}








