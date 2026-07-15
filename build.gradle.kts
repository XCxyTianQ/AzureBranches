/*
 * AzureBranches — Minecraft multi-threaded server (Folia downstream)
 *
 * Build approach inspired by Luminol (AzureSkyline) / Lophine.
 * Thanks to EarthMe & LuminolMC contributors.
 */
plugins { id("java") }

allprojects {
    group = "com.azurebranches"
    version = "26.1.2-AB-0001"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
