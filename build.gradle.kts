plugins {
    java
    id("com.gradleup.shadow") version "9.3.1"
}

group = "org.hyzionstudios"
version = "1.0.1"

repositories {
    mavenCentral()
    maven ( url = "https://maven.hytale.com/release")
    maven ( url = "https://maven.hytale.com/pre-release")

    // PlaceholderAPI
    maven ( url = "https://repo.helpch.at/releases/")

    // Vault Unlocked Repo
    maven ( url = "https://repo.codemc.io/repository/creatorfromhell/")
}

val hytaleInstallPath: String by project
val hytaleServerJarPath: String by project

val resolvedServerJar = hytaleServerJarPath.ifBlank { "$hytaleInstallPath/Server/HytaleServer.jar" }

dependencies {
    // Hytale Server API from official Maven repository
    compileOnly("com.hypixel.hytale:Server:0.5.6")

    // PlaceholderAPI
    compileOnly("at.helpch:placeholderapi-hytale:1.0.8")

    // Luckperms
    compileOnly("net.luckperms:api:5.5")

    // Vault Unlocked
    compileOnly("net.cfh.vault:VaultUnlocked:2.18.3")

    // MysticVanish (soft integration; local jar — build MysticVanish and copy to libs/)
    compileOnly(files("libs/MysticVanish-1.0.0.jar"))

    // MysticModeration (soft integration; local jar — build MysticModeration and copy to libs/)
    compileOnly(files("libs/MysticModeration-1.0.0.jar"))

    // SQL storage: connection pool + JDBC drivers (shaded into the mod jar).
    // Hytale gives each plugin an isolated PluginClassLoader, so these are bundled
    // without relocation. protobuf is excluded from the MySQL driver (only used by
    // the unused X DevAPI) to keep the jar lean and avoid duplicating the server's.
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.1.2")
    implementation("com.mysql:mysql-connector-j:8.4.0") {
        exclude(group = "com.google.protobuf")
    }

    // Redis: cache + pub/sub for cross-server features. Jedis is netty-free, so it
    // avoids clashing with the server's bundled netty (gson comes from the server).
    implementation("redis.clients:jedis:5.1.0") {
        exclude(group = "com.google.gson")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Preserve JDBC driver auto-registration (META-INF/services/java.sql.Driver).
    mergeServiceFiles()
}

tasks.register<Copy>("deployMod") {
    group = "hytale"
    description = "Builds the mod and copies it to the project-local server mods folder."
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into("$projectDir/.hytale-server/mods")
}

tasks.register("cleanDeploy") {
    group = "hytale"
    description = "Cleans, rebuilds, and deploys the mod."
    dependsOn("clean", "deployMod")
}

tasks.named("deployMod") {
    mustRunAfter("clean")
}
