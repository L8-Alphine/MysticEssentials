package org.hyzionstudios.mysticessentials.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Checks the shipped (shaded) jar itself: every bundled library is relocated under
 * {@code org.hyzionstudios.mysticessentials.libs}, the merged JDBC service file
 * names both relocated drivers and they load through {@link ServiceLoader}, the
 * relocated driver/class-name strings and resources still work, and the public
 * {@code api} package is shipped unchanged.
 *
 * <p>Runs against the jar with only the JDK as parent class loader, so nothing
 * unrelocated on a build classpath can mask a broken relocation. Dependency-free
 * main method, like the other verify tasks.</p>
 *
 * <p>Arguments: the shaded jar, then the compiled main classes directory.</p>
 */
public final class ShadedJarTest {

    private static final String LIBS = "org.hyzionstudios.mysticessentials.libs.";
    private static final String MYSQL_DRIVER = LIBS + "com.mysql.cj.jdbc.Driver";
    private static final String MARIADB_DRIVER = LIBS + "org.mariadb.jdbc.Driver";

    /** Original packages of every bundled library (internal names). */
    private static final List<String> LIBRARY_PACKAGES = List.of(
            "com/zaxxer/hikari/", "org/mariadb/", "com/mysql/", "redis/clients/", "org/jsoup/",
            "org/slf4j/", "org/json/", "org/apache/commons/", "com/google/errorprone/");

    /** Top-level class roots allowed in the jar: ours, the license core, the template runtime. */
    private static final List<String> ALLOWED_ROOTS = List.of(
            "org/hyzionstudios/", "com/mysticlicensing/", "com/azuredoom/hytale/asseteditor/");

    private ShadedJarTest() {
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "usage: ShadedJarTest <shaded jar> <main classes dir>");
        Path jar = Path.of(args[0]);
        Path mainClasses = Path.of(args[1]);
        require(Files.isRegularFile(jar), "shaded jar not found: " + jar);

        try (JarFile file = new JarFile(jar.toFile())) {
            onlyRelocatedLibraries(file);
            driverServiceFileMerged(file);
            apiPackageUnchanged(file, mainClasses);
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            driversLoadThroughServiceLoader(loader);
            mariadbPluginServicesResolve(loader);
            hikariAcceptsRelocatedDriverName(loader);
            jsoupParsesWithItsResources(loader);
            jedisClassesLink(loader);
        }
        System.out.println("ShadedJarTest: " + jar.getFileName() + " OK");
    }

    private static void onlyRelocatedLibraries(JarFile file) {
        List<String> stray = new ArrayList<>();
        for (JarEntry entry : (Iterable<JarEntry>) file.stream()::iterator) {
            String name = entry.getName();
            if (!name.endsWith(".class") || name.equals("module-info.class")) {
                continue;
            }
            // Multi-release entries are checked by their versioned path's remainder.
            String path = name.startsWith("META-INF/versions/")
                    ? name.substring(name.indexOf('/', "META-INF/versions/".length()) + 1)
                    : name;
            if (ALLOWED_ROOTS.stream().noneMatch(path::startsWith)) {
                stray.add(name);
            }
        }
        require(stray.isEmpty(), "unrelocated classes in the shaded jar: "
                + stray.subList(0, Math.min(10, stray.size())) + (stray.size() > 10 ? " ..." : ""));
    }

    private static void driverServiceFileMerged(JarFile file) throws IOException {
        JarEntry entry = file.getJarEntry("META-INF/services/java.sql.Driver");
        require(entry != null, "META-INF/services/java.sql.Driver is missing");
        Set<String> drivers = new TreeSet<>();
        try (InputStream in = file.getInputStream(entry)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    drivers.add(trimmed);
                }
            }
        }
        require(drivers.equals(Set.of(MYSQL_DRIVER, MARIADB_DRIVER)),
                "java.sql.Driver service file should list exactly both relocated drivers: " + drivers);
    }

    /**
     * Our own classes must not be moved by the relocation. An api class may only
     * differ from the compiled one when it uses a bundled library internally (its
     * references to that library are rewritten); every other one is byte-identical.
     */
    private static void apiPackageUnchanged(JarFile file, Path mainClasses) throws IOException {
        Path apiDir = mainClasses.resolve("org/hyzionstudios/mysticessentials/api");
        require(Files.isDirectory(apiDir), "compiled api classes not found: " + apiDir);
        List<String> missing = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        try (Stream<Path> classes = Files.walk(apiDir)) {
            for (Path compiled : (Iterable<Path>) classes.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = mainClasses.relativize(compiled).toString().replace('\\', '/');
                JarEntry entry = file.getJarEntry(name);
                if (entry == null) {
                    missing.add(name);
                    continue;
                }
                byte[] original = Files.readAllBytes(compiled);
                byte[] shipped;
                try (InputStream in = file.getInputStream(entry)) {
                    shipped = in.readAllBytes();
                }
                if (!Arrays.equals(original, shipped) && !referencesLibrary(original)) {
                    changed.add(name);
                }
            }
        }
        require(missing.isEmpty(), "api classes missing from the shaded jar: " + missing);
        require(changed.isEmpty(), "api classes changed by shading without using a library: " + changed);
    }

    private static boolean referencesLibrary(byte[] classFile) {
        String text = new String(classFile, StandardCharsets.ISO_8859_1);
        return LIBRARY_PACKAGES.stream().anyMatch(text::contains);
    }

    private static void driversLoadThroughServiceLoader(ClassLoader loader) {
        Set<String> found = new TreeSet<>();
        Driver mysql = null;
        Driver mariadb = null;
        for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
            String name = driver.getClass().getName();
            found.add(name);
            if (name.equals(MYSQL_DRIVER)) {
                mysql = driver;
            } else if (name.equals(MARIADB_DRIVER)) {
                mariadb = driver;
            }
        }
        require(mysql != null && mariadb != null, "ServiceLoader did not find both drivers: " + found);
        try {
            require(mysql.acceptsURL("jdbc:mysql://localhost:3306/mystic"), "MySQL driver rejects its URL");
            require(mariadb.acceptsURL("jdbc:mariadb://localhost:3306/mystic"), "MariaDB driver rejects its URL");
        } catch (Exception e) {
            throw new AssertionError("driver URL check failed", e);
        }
    }

    /** The relocated plugin service files must name classes that implement the relocated interfaces. */
    private static void mariadbPluginServicesResolve(ClassLoader loader) throws ClassNotFoundException {
        for (String service : List.of("Codec", "AuthenticationPluginFactory", "CredentialPlugin",
                "TlsSocketPlugin")) {
            Class<?> type = Class.forName(LIBS + "org.mariadb.jdbc.plugin." + service, false, loader);
            int count = 0;
            for (Object ignored : ServiceLoader.load(type, loader)) {
                count++;
            }
            require(count > 0, "no MariaDB " + service + " implementations load from the shaded jar");
        }
    }

    private static void hikariAcceptsRelocatedDriverName(ClassLoader loader) throws Exception {
        Class<?> config = Class.forName(LIBS + "com.zaxxer.hikari.HikariConfig", true, loader);
        Object hikari = config.getConstructor().newInstance();
        Method setDriver = config.getMethod("setDriverClassName", String.class);
        // The same class names SqlStorageProvider passes after Shadow rewrote them.
        setDriver.invoke(hikari, MYSQL_DRIVER);
        setDriver.invoke(hikari, MARIADB_DRIVER);
    }

    private static void jsoupParsesWithItsResources(ClassLoader loader) throws Exception {
        Class<?> jsoup = Class.forName(LIBS + "org.jsoup.Jsoup", true, loader);
        Object document = jsoup.getMethod("parse", String.class)
                .invoke(null, "<p title=\"x\">a &amp; b &copy;</p>");
        Object text = document.getClass().getMethod("text").invoke(document);
        require("a & b ©".equals(text), "jsoup parsed entities wrong: " + text);
    }

    private static void jedisClassesLink(ClassLoader loader) throws Exception {
        Class<?> hostAndPort = Class.forName(LIBS + "redis.clients.jedis.HostAndPort", true, loader);
        Object endpoint = hostAndPort.getConstructor(String.class, int.class).newInstance("localhost", 6379);
        require("localhost:6379".equals(endpoint.toString()), "Jedis HostAndPort misbehaves: " + endpoint);
        Class.forName(LIBS + "redis.clients.jedis.RedisClient", false, loader);
        Class.forName(LIBS + "redis.clients.jedis.DefaultJedisClientConfig", false, loader);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
