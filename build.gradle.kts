// Pinned to versions known to build against android-36.
//
// The Google services Gradle plugin is deliberately absent: Firebase is initialised at runtime from
// values injected at build time, so no one's google-services.json ever enters this repository.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
