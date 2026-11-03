plugins {
    // Versions are chosen for mutual compatibility (Gradle 8.10.2 / JDK 17+) but are verified only by CI.
    id("com.android.application") version "8.7.3" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
}
