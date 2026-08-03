plugins {
    java
}

group = "com.mysticlicensing"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // One-way: the adapter knows about the core, never the reverse.
    implementation(project(":mystic-license-core"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}
