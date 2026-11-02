/*
 * Renders Liquid Glass on a stand-in for Apple Music's layout, on the JVM: Robolectric runs the
 * real Android rendering pipeline (hwui), so no phone and no Apple Music APK are needed.
 *
 *   ./gradlew -p tools/glass-preview testDebugUnitTest
 *
 * Writes build/preview/*.png. Needs an Android SDK (ANDROID_HOME or local.properties).
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "glass-preview"
