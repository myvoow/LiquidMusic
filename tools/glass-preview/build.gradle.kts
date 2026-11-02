plugins {
    id("com.android.library") version "9.5.0"
}

android {
    namespace = "app.liquidmusic.preview"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
    }
    // The extension is compiled as is, from the patch sources (or from another copy of them,
    // with -PextensionSources=<dir>, to compare versions).
    sourceSets["main"].java.directories.add(
        providers.gradleProperty("extensionSources").orNull
            ?: rootDir.resolve("../../extensions/liquidglass/src/main/java").canonicalPath
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Draw with HardwareRenderer, so RenderNode, RenderEffect and AGSL really run.
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("preview.out", layout.buildDirectory.dir("preview").get().asFile.absolutePath)
                // Optional Maven mirror for the Android framework jars Robolectric downloads.
                providers.gradleProperty("robolectricRepo").orNull?.let { repo ->
                    it.systemProperty("robolectric.dependency.repo.url", repo)
                    it.systemProperty("robolectric.dependency.repo.id", "preview-mirror")
                }
                it.maxHeapSize = "3g"
            }
        }
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
