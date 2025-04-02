plugins {
    kotlin("jvm")
}


kotlin {
    jvmToolchain {
        this.languageVersion.set(JavaLanguageVersion.of(17))
        this.vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    tasks.named("jar").configure {
        dependsOn("compileKotlin")
    }
}


dependencies {
    compileOnly(libs.pbandk.runtime)
    compileOnly(libs.pbandk.protoc.gen)
}