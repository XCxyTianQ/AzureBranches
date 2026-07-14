pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "azurebranches"

for ((projectName, dirName) in listOf("azurebranches-api" to "folia-api", "azurebranches-server" to "folia-server")) {
    include(projectName)
    project(":$projectName").projectDir = file(dirName)
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val foliaVersionChannel = providers.gradleProperty("channel").get().trim()
    val foliaBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (foliaBuildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$foliaBuildNumber-${foliaVersionChannel.lowercase()}"
    }
    version = versionString
}