plugins {
    java
}

group = "net.kaltra"
version = "1.0.0-beta.8"

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.1.build.29-alpha")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:all,-deprecation,-removal", "-Werror"))
    options.encoding = "UTF-8"
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

// The verification source is executed by verifySoundIcons rather than JUnit.
// Gradle 9 otherwise fails the empty Test task before the JavaExec verifier runs.
tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    failOnNoDiscoveredTests = false
}

tasks.jar {
    archiveBaseName.set("KaltraSounds")
    archiveVersion.set(project.version.toString())
}

val verifySoundIcons by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs dependency-free GUI sound icon mapping checks."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("net.kaltra.sounds.SoundIconResolverVerification")
}

tasks.check {
    dependsOn(verifySoundIcons)
}
