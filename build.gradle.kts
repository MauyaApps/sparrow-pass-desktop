import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.12.2"
}

repositories { google(); mavenCentral() }

dependencies {
    implementation(compose.desktop.currentOS)
    implementation(compose.material3)
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "sparrow.ui.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Sparrow"
            packageVersion = "0.1.0"
            vendor = "Mauya Apps"
            description = "Sparrow password manager"
            includeAllModules = true // safe choice; try removing later to shrink the installer
            windows {
                menu = true
                shortcut = true
                upgradeUuid = "6f1d0c2e-3b7a-4d55-9a1e-5c0d7e2b9a41"
            }
        }
    }
}
