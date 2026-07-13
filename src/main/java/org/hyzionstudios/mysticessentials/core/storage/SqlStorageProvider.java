package org.hyzionstudios.mysticessentials.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.config.MainConfig;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonElement;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Relational storage provider for MySQL and MariaDB, backed by a HikariCP
 * connection pool. Documents are stored in a single {@code mystic_documents}
 * table keyed by {@code (namespace, id)} with the JSON payload in a
 * {@code LONGTEXT} column — the same document model the JSON provider exposes, so
 * modules are portable across providers.
 *
 * <p>Both flavours share this class; {@code mysql} selects the MySQL Connector/J
 * driver and {@code mariadb} the MariaDB driver (both bundled). If the database
 * is unreachable, {@link #init()} throws and the Core falls back to JSON so the
 * server still boots.</p>
 */
public final class SqlStorageProvider implements StorageProvider {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS mystic_documents ("
                    + "namespace VARCHAR(128) NOT NULL, "
                    + "id VARCHAR(128) NOT NULL, "
                    + "data LONGTEXT NOT NULL, "
                    + "PRIMARY KEY (namespace, id))";
    private static final String UPSERT =
            "INSERT INTO mystic_documents (namespace, id, data) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE data = VALUES(data)";
    private static final String SELECT = "SELECT data FROM mystic_documents WHERE namespace = ? AND id = ?";
    private static final String DELETE = "DELETE FROM mystic_documents WHERE namespace = ? AND id = ?";
    private static final String EXISTS = "SELECT 1 FROM mystic_documents WHERE namespace = ? AND id = ? LIMIT 1";
    private static final String LIST_KEYS = "SELECT id FROM mystic_documents WHERE namespace = ?";

    private final MysticCore core;
    private final MainConfig.Mysql config;
    private final String flavour;

    private HikariDataSource dataSource;
    private ExecutorService executor;

    public SqlStorageProvider(MysticCore core, MainConfig.Mysql config, String flavour) {
        this.core = core;
        this.config = config;
        this.flavour = flavour;
    }

    @Override
    public String id() {
        return flavour;
    }

    @Override
    public void init() throws SQLException {
        boolean mysql = "mysql".equals(flavour);
        String url = "jdbc:" + (mysql ? "mysql" : "mariadb") + "://"
                + config.host + ":" + config.port + "/" + config.database;

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("MysticEssentials-" + flavour);
        hikari.setJdbcUrl(url);
        hikari.setUsername(config.username);
        hikari.setPassword(config.password);
        hikari.setMaximumPoolSize(Math.max(1, config.poolSize));
        hikari.setDriverClassName(mysql ? "com.mysql.cj.jdbc.Driver" : "org.mariadb.jdbc.Driver");

        this.dataSource = new HikariDataSource(hikari);

        int poolSize = Math.max(1, config.poolSize);
        this.executor = Executors.newFixedThreadPool(poolSize, namedFactory());

        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.execute();
        }
        core.log(Level.INFO, "Connected to " + flavour + " at " + config.host + ":" + config.port
                + "/" + config.database + " (pool=" + poolSize + ")");
    }

    private ThreadFactory namedFactory() {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "MysticEssentials-SQL-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    @Override
    public CompletableFuture<JsonElement> load(String namespace, String key) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(SELECT)) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Json.parse(rs.getString(1)) : null;
                }
            } catch (SQLException e) {
                throw new JsonStorageProvider.StorageException("load " + namespace + "/" + key, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> save(String namespace, String key, JsonElement value) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                statement.setString(3, Json.toString(value));
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new JsonStorageProvider.StorageException("save " + namespace + "/" + key, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> delete(String namespace, String key) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(DELETE)) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                return statement.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new JsonStorageProvider.StorageException("delete " + namespace + "/" + key, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> exists(String namespace, String key) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(EXISTS)) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new JsonStorageProvider.StorageException("exists " + namespace + "/" + key, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<java.util.List<String>> listKeys(String namespace) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(LIST_KEYS)) {
                statement.setString(1, namespace);
                try (ResultSet rs = statement.executeQuery()) {
                    java.util.List<String> keys = new java.util.ArrayList<>();
                    while (rs.next()) {
                        keys.add(rs.getString(1));
                    }
                    return keys;
                }
            } catch (SQLException e) {
                throw new JsonStorageProvider.StorageException("listKeys " + namespace, e);
            }
        }, executor);
    }

    @Override
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
