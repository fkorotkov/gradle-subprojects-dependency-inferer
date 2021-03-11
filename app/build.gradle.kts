plugins {
    application
    id("org.jetbrains.kotlin.jvm") version "1.4.20"
    id("org.jetbrains.intellij") version "0.7.2"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    jcenter()
}

intellij {
    version = "2020.3"
    setPlugins("java", "Kotlin")
    isDownloadSources = true
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.google.guava:guava:29.0-jre")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

application {
    // Define the main class for the application.
    mainClass.set("com.fkorotkov.gradle.infer.AppKt")
}
