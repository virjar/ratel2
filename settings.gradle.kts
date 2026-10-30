pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "ratel"

include(":module-api:api")
include(":module-api:plugin-base")
include(":runtime")
include(":toolchain")
include(":examples:demo-module")
include(":examples:dev-plugin")
include(":examples:native-module")
include(":examples:split-app")
include(":examples:split-apks-tool")
include(":examples:e2e-host")
