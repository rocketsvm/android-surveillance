import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Desktop harness: the whole server/web UI with a synthetic H.264 source (see src/test/.../dev/DevServer.kt).
tasks.register<JavaExec>("runDev") {
    group = "application"
    description = "Runs the web server on the desktop with a fake camera"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.surveil.core.dev.DevServerKt")
    workingDir = rootProject.file(".")
    args = (project.findProperty("devArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}
