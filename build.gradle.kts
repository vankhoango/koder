plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.clikt)
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("dev.kode.koder.MainKt")
    applicationName = "Koder"
}

tasks.test { useJUnitPlatform() }

// Without this, `./gradlew run` can't read keyboard input, and our REPL needs it.
tasks.named<JavaExec>("run") { standardInput = System.`in` }