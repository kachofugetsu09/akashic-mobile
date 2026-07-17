import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test

abstract class VerifyProtocolSnapshot : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val snapshotFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val metadata = JsonSlurper().parse(sourceFile.get().asFile) as Map<*, *>
        val snapshotPath = requireNotNull(metadata["snapshot_path"] as? String) {
            "protocol/source.json is missing snapshot_path"
        }
        check(snapshotPath == "protocol/mobile-realtime-v1.json") {
            "Unexpected protocol snapshot path: $snapshotPath"
        }
        val expected = requireNotNull(metadata["sha256"] as? String) {
            "protocol/source.json is missing sha256"
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(snapshotFile.get().asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == expected) {
            "Protocol snapshot SHA-256 mismatch: expected=$expected actual=$actual"
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.akashic.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.akashic.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_INSECURE_WS", "true")
        }
        release {
            isMinifyEnabled = true
            buildConfigField("boolean", "ALLOW_INSECURE_WS", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val repositoryRoot = rootProject.projectDir.parentFile
val protocolSource = repositoryRoot.resolve("protocol/source.json")
val verifyProtocolSnapshot by tasks.registering(VerifyProtocolSnapshot::class) {
    sourceFile.set(protocolSource)
    snapshotFile.set(repositoryRoot.resolve("protocol/mobile-realtime-v1.json"))
}

tasks.named("preBuild").configure { dependsOn(verifyProtocolSnapshot) }
tasks.named("check").configure { dependsOn(verifyProtocolSnapshot) }
tasks.withType<Test>().configureEach {
    dependsOn(verifyProtocolSnapshot)
    systemProperty("akashic.repositoryRoot", repositoryRoot.absolutePath)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.google.material)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
