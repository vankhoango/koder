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
    implementation(libs.openai)

    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("net.khoango.koder.MainKt")
    applicationName = "Koder"
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.test { useJUnitPlatform() }

// Without this, `./gradlew run` can't read keyboard input, and our REPL needs it.
tasks.named<JavaExec>("run") { standardInput = System.`in` }