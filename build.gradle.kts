import java.util.zip.ZipFile

plugins {
    java
    id("com.gradleup.shadow") version "9.3.1"
}

group = "org.hyzionstudios"
version = "1.0.2"

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

    // Offline license verification. Zero runtime dependencies of its own, so it
    // shades in cleanly and cannot collide with anything on the server.
    implementation(project(":mystic-license-core"))

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

    // CustomGUIs: parse declarative .gui.html documents, including the legacy
    // standalone format, without depending on its HyUI runtime.
    implementation("org.jsoup:jsoup:1.18.3")
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

/**
 * Validates every `$C.@Component { ... }` instantiation in our shipped `.ui`
 * documents against the parameter contract declared in the game's `Common.ui`.
 *
 * A component parameter that Common.ui references inside its body but never
 * gives a default (`@Text` on the TextButton family) is required. Omitting it
 * leaves the property unresolved, and that failure is NOT contained to the
 * offending document: the client stops resolving documents belonging to other
 * asset packs, so unrelated mods disconnect every player at world join
 * (diagnosed 2026-07-28). Build time is the only place this cannot reach a
 * client.
 *
 * Skips when the game assets are not installed so CI still builds.
 */
tasks.register("validateUiDocuments") {
    group = "verification"
    description = "Checks shipped .ui documents supply every required Common.ui parameter."

    val uiDir = layout.projectDirectory.dir("src/main/resources/Common/UI/Custom")
    val assetsZip = File("$hytaleInstallPath/Assets.zip")
    inputs.dir(uiDir)
    outputs.upToDateWhen { false }

    doLast {
        if (!assetsZip.isFile) {
            logger.lifecycle("validateUiDocuments: ${assetsZip.path} not found, skipping.")
            return@doLast
        }
        val commonUi = ZipFile(assetsZip).use { zip ->
            val entry = zip.getEntry("Common/UI/Custom/Common.ui")
            if (entry == null) null else zip.getInputStream(entry).bufferedReader().readText()
        }
        if (commonUi == null) {
            logger.lifecycle("validateUiDocuments: Common.ui not in Assets.zip, skipping.")
            return@doLast
        }

        // File-scope constants resolve from anywhere, so they are never a
        // caller's responsibility to supply.
        val fileScope = Regex("""(?m)^@(\w+)\s*=""").findAll(commonUi)
            .map { it.groupValues[1] }.toSet()

        // A component's required parameters: referenced in its body, given no
        // default there, and not a file-scope constant.
        val required = mutableMapOf<String, Set<String>>()
        Regex("""(?m)^@(\w+)\s*=\s*\w+\s*\{(.*?)^\};""", RegexOption.DOT_MATCHES_ALL)
            .findAll(commonUi).forEach { match ->
                val body = match.groupValues[2]
                val declared = Regex("""@(\w+)\s*=""").findAll(body).map { it.groupValues[1] }.toSet()
                // Skip qualified references such as $Sounds.@ButtonsLight -- those
                // resolve through another import, not through a parameter.
                val referenced = Regex("""(?<![.\w])@(\w+)""").findAll(body)
                    .map { it.groupValues[1] }.toSet()
                val missing = referenced - declared - fileScope
                if (missing.isNotEmpty()) required[match.groupValues[1]] = missing
            }

        val dollar = '$'
        // The dollar must be escaped: bare "$" is the regex end-of-line anchor.
        val header = Regex("\\" + dollar + """C\.@(\w+)\s*(?:#\w+)?\s*\{""")
        val problems = mutableListOf<String>()
        var checked = 0

        uiDir.asFile.walkTopDown().filter { it.isFile && it.extension == "ui" }.forEach { file ->
            val text = file.readText()
            header.findAll(text).forEach { use ->
                val component = use.groupValues[1]
                checked++
                val needed = required[component].orEmpty()
                if (needed.isNotEmpty()) {
                    // Brace-match so nested blocks are not truncated.
                    var depth = 1
                    var index = use.range.last + 1
                    while (index < text.length && depth > 0) {
                        when (text[index]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        index++
                    }
                    val start = use.range.last + 1
                    val body = text.substring(start, maxOf(start, index - 1))
                    needed.sorted().forEach { parameter ->
                        if (!Regex("@" + parameter + """\s*=""").containsMatchIn(body)) {
                            problems += file.name + ": " + dollar + "C.@" + component +
                                " is missing required @" + parameter
                        }
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Shipped .ui documents omit required Common.ui parameters. These break OTHER mods' " +
                    "documents on the client and disconnect players at world join:\n  " +
                    problems.joinToString("\n  ")
            )
        }
        logger.lifecycle(
            "validateUiDocuments: " + checked + " component instantiation(s) checked against " +
                required.size + " parameterised component(s); all satisfied."
        )
    }
}

tasks.named("check") { dependsOn("validateUiDocuments") }
tasks.named("shadowJar") { dependsOn("validateUiDocuments") }
