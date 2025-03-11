plugins {
    kotlin("jvm")
}

val pbandkVersion = "0.14.3"

kotlin {
    jvmToolchain(17)
}
// 需要保证当前gradle模块中的jar命令在:shared:generator:protobuf-codegen的compileKotlin之前执行


dependencies {
    compileOnly("pro.streem.pbandk:pbandk-runtime:$pbandkVersion")
    compileOnly("pro.streem.pbandk:protoc-gen-pbandk-lib:$pbandkVersion")
}