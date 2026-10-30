plugins {
    id("com.android.library") version "9.4.1" apply false
    id("com.android.application") version "9.4.1" apply false
    id("com.android.dynamic-feature") version "9.4.1" apply false
}

allprojects {
    version = "2.0"
}

subprojects {
    repositories {
        mavenCentral()
        google()
    }
}
