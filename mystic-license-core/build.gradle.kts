plugins {
    java
}

group = "com.mysticlicensing"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // No runtime dependencies, on purpose. See README.md ("Why there is no JSON
    // library"): this jar lands on a mod classpath we do not control, so it
    // carries nothing that could collide with the server's own libraries.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Compiled against the JDK the rest of the build already uses, but emitting
    // Java 17 bytecode: Ed25519 needs 15+, and 17 is the oldest LTS a Hytale
    // server realistically runs. Keeping the floor low means the same jar drops
    // into every Mystic mod regardless of its own toolchain.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
    }
}
