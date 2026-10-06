import java.util.zip.ZipFile

plugins {
    idea
    java
    id("com.azuredoom.hytale-tools") version "1.+"
    id("com.gradleup.shadow") version "9.6.1"
}

tasks.withType<Javadoc>().configureEach {
    (options as org.gradle.external.javadoc.StandardJavadocDocletOptions).addStringOption("Xdoclint:-missing", "-quiet")
}

group = project.property("group").toString()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(property("java_version").toString().toInt()))
}

hytaleTools {
    javaVersion = property("java_version").toString().toInt()
    hytaleVersion = property("hytale_version").toString()
    manifestServerVersion = property("manifestServerVersion").toString()
    manifestGroup = property("manifest_group").toString()
    modId = property("mod_id").toString()
    modDescription = property("mod_description").toString()
    modUrl = property("mod_url").toString()
    mainClass = property("main_class").toString()
    modCredits = property("mod_author").toString()
    manifestDependencies = property("manifest_dependencies").toString()
    manifestOptionalDependencies = property("manifest_opt_dependencies").toString()
    curseforgeId = property("curseforgeID").toString()
    disabledByDefault = property("disabled_by_default").toString().toBoolean()
    includesPack = property("includes_pack").toString().toBoolean()
    injectServerJavadocsIntoSources = property("inject_server_javadocs_into_sources").toString().toBoolean()
    generateAssetsBinary = property("generateAssetsBinary").toString().toBoolean()
    patchline = property("patchline").toString()
}

repositories {
    mavenCentral()
    // The Hytale and PlaceholderAPI repositories are added by hytale-tools.

    // Vault Unlocked Repo
    maven(url = "https://repo.codemc.io/repository/creatorfromhell/")
}

dependencies {
    // The Hytale Server API is added to compileOnly by hytale-tools from hytale_version.
    // testCompileOnly does not inherit compileOnly, so the tests name the server jar explicitly.
    testCompileOnly("com.hypixel.hytale:Server:${property("hytale_version")}")

    // Offline license verification. Zero runtime dependencies of its own, so it
    // shades in cleanly and cannot collide with anything on the server.
    implementation(project(":mystic-license-core"))

    // PlaceholderAPI
    compileOnly("at.helpch:placeholderapi-hytale:1.0.8")

    // Luckperms
    compileOnly("net.luckperms:api:5.5")

    // Vault Unlocked
    compileOnly("net.cfh.vault:VaultUnlocked:2.20.1") { isTransitive = false }

    // SQL storage: connection pool + JDBC drivers (shaded into the mod jar and
    // relocated in the shadowJar block below). protobuf is excluded from the MySQL
    // driver (only used by the unused X DevAPI) to keep the jar lean and avoid
    // duplicating the server's.
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.8")
    implementation("com.mysql:mysql-connector-j:26.7.0") {
        exclude(group = "com.google.protobuf")
    }

    // Redis: cache + pub/sub for cross-server features. Jedis is netty-free, so it
    // avoids clashing with the server's bundled netty (gson comes from the server).
    implementation("redis.clients:jedis:7.4.1") {
        // gson's group is com.google.code.gson - the old "com.google.gson" spelling
        // never matched, so a second copy shipped alongside the server's.
        exclude(group = "com.google.code.gson")
    }

    // CustomGUIs: parse declarative .gui.html documents, including the legacy
    // standalone format, without depending on its HyUI runtime.
    implementation("org.jsoup:jsoup:1.23.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}

// The plain jar has no Jedis/JDBC/jsoup inside and must never be what gets
// deployed: give it a classifier so it cannot overwrite the shaded jar, which
// keeps the bare MysticEssentials-<version>.jar name. (`gradle build` runs both
// tasks, and whichever finished last used to win.)
tasks.named<Jar>("jar") {
    archiveBaseName.set(project.property("mod_name").toString())
    archiveVersion.set(project.property("version").toString())
    archiveClassifier.set("thin")
}

tasks.shadowJar {
    archiveBaseName.set(project.property("mod_name").toString())
    archiveClassifier.set("")
    // Preserve JDBC driver auto-registration (META-INF/services/java.sql.Driver).
    // MariaDB and MySQL both ship that file. Shadow 9 defaults to EXCLUDE, which drops
    // the duplicate path before mergeServiceFiles can combine them, leaving only one
    // driver discoverable.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()

    // Move every bundled library into our own namespace. Sibling Mystic mods bundle
    // the same libraries at other versions, and a PluginClassLoader still resolves
    // classes and META-INF/services entries that other plugins bundle, so an
    // unrelocated copy lets theirs and ours mix (a newer mariadb's codec list loaded
    // against an older Codec interface fails with "not a subtype"). Shadow also
    // rewrites the driver class-name strings (SqlStorageProvider) and the service
    // files. Our own packages, the public api included, are not touched.
    // verifyShadedJar checks the result.
    listOf(
        "com.zaxxer.hikari",
        "org.mariadb",
        "com.mysql",
        "redis.clients",
        "org.jsoup",
        "org.slf4j",
        "org.json",
        "org.apache.commons",
        "com.google.errorprone"
    ).forEach { relocate(it, "org.hyzionstudios.mysticessentials.libs.$it") }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

// The MysticIdentity web-portal adapter (Player Portal bible §8). A portal provider must
// implement MysticIdentity's interface, and the main source set never compiles against a Mystic
// plugin's API (IntegrationContractTest.localMysticBridgesRemainUnbundled), so the adapter has a
// source set of its own: compiled against the MysticIdentity API jar and our main classes,
// bundled into the mod jar, and loaded by name by core.integration.PortalBridge only when
// MysticIdentity is installed.
val mysticIdentity: SourceSet = sourceSets.create("mysticIdentity") {
    java.srcDir("src/mysticidentity/java")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}

dependencies {
    "mysticIdentityCompileOnly"(files("../MysticIdentity/mysticidentity-api/build/libs/mysticidentity-api-0.1.0.jar"))
}

tasks.shadowJar {
    from(mysticIdentity.output)
}

// Its checks, in the same dependency-free main-method style as the other verify tasks below.
val mysticIdentityCheck: SourceSet = sourceSets.create("mysticIdentityCheck") {
    java.srcDir("src/mysticidentityCheck/java")
    compileClasspath += mysticIdentity.output + mysticIdentity.compileClasspath
    runtimeClasspath += output + compileClasspath
}

tasks.register<JavaExec>("verifyPortalAdapter") {
    group = "verification"
    description = "Checks the MysticIdentity portal adapter's read-only renderings."
    dependsOn(mysticIdentityCheck.classesTaskName)
    classpath = mysticIdentityCheck.runtimeClasspath
    mainClass.set("org.hyzionstudios.mysticessentials.integration.mysticidentity.EssentialsPortalCheck")
}

tasks.named("check") { dependsOn("verifyPortalAdapter") }

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
 * The game's Assets.zip comes from `hytale_home` (an Assets.zip, a folder holding
 * one, or a Hytale install root -- what runServer accepts), else from the copy
 * hytale-tools downloads for runServer. Skips when neither exists so CI still builds.
 */
val uiAssetsZipCandidates: List<File> = run {
    val patchline = property("patchline").toString()
    val hytaleHome = providers.gradleProperty("hytale_home").orNull?.trim().orEmpty()
    (if (hytaleHome.isEmpty()) emptyList() else listOf(
        File(hytaleHome),
        File(hytaleHome, "Assets.zip"),
        File(hytaleHome, "install/$patchline/package/game/latest/Assets.zip")
    )) + gradle.gradleUserHomeDir.resolve(
        "caches/hytale-assets/$patchline-${property("hytale_version")}-Assets.zip"
    )
}

tasks.register("validateUiDocuments") {
    group = "verification"
    description = "Checks shipped .ui documents supply every required Common.ui parameter."

    val uiDir = layout.projectDirectory.dir("src/main/resources/Common/UI/Custom")
    inputs.dir(uiDir)
    outputs.upToDateWhen { false }

    doLast {
        val assetsZip = uiAssetsZipCandidates.firstOrNull { it.isFile }
        if (assetsZip == null) {
            logger.lifecycle("validateUiDocuments: no Assets.zip (set hytale_home), skipping.")
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

        // A component's required parameters: referenced in its body, given no
        // default there, and not a file-scope constant. Derived for the game's
        // Common.ui and again for our own MysticTheme.ui, which pages import as
        // $M and which is held to the same contract.
        fun requiredParameters(source: String): Map<String, Set<String>> {
            // File-scope constants resolve from anywhere, so they are never a
            // caller's responsibility to supply.
            val fileScope = Regex("""(?m)^@(\w+)\s*=""").findAll(source)
                .map { it.groupValues[1] }.toSet()
            val required = mutableMapOf<String, Set<String>>()
            Regex("""(?m)^@(\w+)\s*=\s*\w+\s*\{(.*?)^\};""", RegexOption.DOT_MATCHES_ALL)
                .findAll(source).forEach { match ->
                    val body = match.groupValues[2]
                    val declared = Regex("""@(\w+)\s*=""").findAll(body).map { it.groupValues[1] }.toSet()
                    // Skip qualified references such as $Sounds.@ButtonsLight -- those
                    // resolve through another import, not through a parameter.
                    val referenced = Regex("""(?<![.\w])@(\w+)""").findAll(body)
                        .map { it.groupValues[1] }.toSet()
                    val missing = referenced - declared - fileScope
                    if (missing.isNotEmpty()) required[match.groupValues[1]] = missing
                }
            return required
        }

        val themeFile = uiDir.file("MysticEssentials/MysticTheme.ui").asFile
        val contracts = mutableMapOf("C" to requiredParameters(commonUi))
        if (themeFile.isFile) {
            val themeRequired = requiredParameters(themeFile.readText())
            // The theme itself must never introduce a required parameter: every
            // page instantiates its components, and one omission would break the
            // whole pack the same way a bare $C.@TextButton does.
            if (themeRequired.isNotEmpty()) {
                throw GradleException(
                    "MysticTheme.ui components must default every parameter; missing defaults: " +
                        themeRequired.entries.joinToString("; ") { "@" + it.key + " -> " + it.value.sorted() }
                )
            }
            contracts["M"] = themeRequired
        }

        val dollar = '$'
        // The dollar must be escaped: bare "$" is the regex end-of-line anchor.
        val header = Regex("\\" + dollar + """([CM])\.@(\w+)\s*(?:#\w+)?\s*\{""")
        val problems = mutableListOf<String>()
        var checked = 0

        uiDir.asFile.walkTopDown().filter { it.isFile && it.extension == "ui" }.forEach { file ->
            val text = file.readText()
            header.findAll(text).forEach { use ->
                val alias = use.groupValues[1]
                val component = use.groupValues[2]
                checked++
                val needed = contracts[alias]?.get(component).orEmpty()
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
                            problems += file.name + ": " + dollar + alias + ".@" + component +
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
                contracts.values.sumOf { it.size } + " parameterised component(s); all satisfied."
        )
    }
}

tasks.named("check") { dependsOn("validateUiDocuments") }
tasks.named("shadowJar") { dependsOn("validateUiDocuments") }

tasks.register<JavaExec>("verifyItemMetadataCompatibility") {
    group = "verification"
    description = "Checks supported custom-item BSON contracts."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set(
        "org.hyzionstudios.mysticessentials.core.item.ModItemMetadataCompatibilityTest"
    )
}

tasks.register<JavaExec>("verifyItemDetailsLayout") {
    group = "verification"
    description = "Checks the Item Details panel reserves height for every drawn line."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set(
        "org.hyzionstudios.mysticessentials.modules.chat.itemlink.ItemDetailsLayoutTest"
    )
}

tasks.register<JavaExec>("verifyMysticRpgRtpSafety") {
    group = "verification"
    description = "Checks MysticRPG-aware RTP level-band safety decisions."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set(
        "org.hyzionstudios.mysticessentials.modules.teleportation.rtp.MysticRpgRtpSafetyTest"
    )
}

tasks.register<JavaExec>("verifyRtpFluidSafety") {
    group = "verification"
    description = "Checks RTP handling of Hytale fluid ids and missing-section sentinels."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set(
        "org.hyzionstudios.mysticessentials.platform.RtpFluidSafetyTest"
    )
}

tasks.register<JavaExec>("verifyIntegrationContracts") {
    group = "verification"
    description = "Checks optional integration metadata and every external API contract."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set(
        "org.hyzionstudios.mysticessentials.core.integration.IntegrationContractTest"
    )
}

// Runs against the shipped jar itself, with only the JDK beside it: every bundled
// library relocated, both JDBC drivers loadable through ServiceLoader, relocated
// resources and class-name strings working, the public api package unchanged.
tasks.register<JavaExec>("verifyShadedJar") {
    group = "verification"
    description = "Checks the shaded jar's relocated libraries, JDBC drivers and api package."
    dependsOn(tasks.testClasses, tasks.shadowJar)
    classpath = sourceSets.test.get().output
    mainClass.set("org.hyzionstudios.mysticessentials.core.storage.ShadedJarTest")
    val shadedJar = tasks.shadowJar.flatMap { it.archiveFile }
    val mainClasses = sourceSets.main.get().java.classesDirectory
    inputs.file(shadedJar)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(shadedJar.get().asFile.absolutePath, mainClasses.get().asFile.absolutePath)
    })
}

tasks.register<JavaExec>("verifyMessageParamOrder") {
    group = "verification"
    description = "Checks message params are filled after placeholders, so they never expand one."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set(
        "org.hyzionstudios.mysticessentials.core.message.MessageParamOrderTest"
    )
}

// Compatibility checks use a dependency-free main method rather than a test
// framework; Gradle 9 otherwise treats their presence as a discovery failure.
tasks.test { failOnNoDiscoveredTests = false }
tasks.named("check") {
    dependsOn(
        "verifyItemMetadataCompatibility",
        "verifyItemDetailsLayout",
        "verifyMysticRpgRtpSafety",
        "verifyRtpFluidSafety",
        "verifyIntegrationContracts",
        "verifyShadedJar",
        "verifyMessageParamOrder"
    )
}
