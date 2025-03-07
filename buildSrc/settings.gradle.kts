@file:Suppress("UnstableApiUsage")

import org.gradle.kotlin.dsl.module
import org.gradle.kotlin.dsl.version


rootProject.name = "buildSrc"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")



pluginManagement {
    repositories.apply {
        removeAll(this)
    }
    dependencyResolutionManagement.repositories.apply {
        removeAll(this)
    }
    listOf(repositories, dependencyResolutionManagement.repositories).forEach {
        it.apply {
            gradlePluginPortal() {
                content {
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }
            google() {
                content {
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }
            mavenCentral() {
                content {
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }
        }
    }
    resolutionStrategy {
        eachPlugin {
            println("requested.id==>>>${requested.id.namespace}:${requested.id.name}")
            if(
                requested.id.namespace=="org.jetbrains.kotlin"
                && requested.id.name.startsWith("kotlin")==true
            ){
                useVersion("2.1.255-SNAPSHOT")
            }else if(
                requested.id.namespace=="org.jetbrains"
                && requested.id.name.startsWith("kotlin")==true
            ){
                useVersion("2.1.255-SNAPSHOT")
            }
        }
    }

}

//plugins {
//    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
//}


dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral {
            content {
                excludeGroup("org.jetbrains.kotlin")
                excludeGroupByRegex("org.jetbrains.kotlin.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        google {
            content {
                includeGroupByRegex(".*google.*")
                includeGroupByRegex(".*android.*")
                excludeGroupByRegex("org.jetbrains.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}