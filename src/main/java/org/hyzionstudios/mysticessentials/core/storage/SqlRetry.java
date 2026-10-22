package org.hyzionstudios.mysticessentials.core.storage;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Which SQL failures of an atomic update are a lost race worth retrying
 * (MySQL and MariaDB codes): a deadlock (1213), a lock wait timeout (1205), a
 * serialization failure (SQLSTATE 40001), or a duplicate primary key when two
 * servers inserted the same new document (1062). Everything else is a real
 * failure.
 */
final class SqlRetry {

    static final int DEADLOCK = 1213;
    static final int LOCK_WAIT_TIMEOUT = 1205;
    static final int DUPLICATE_KEY = 1062;

    private SqlRetry() {
    }

    static boolean retryable(SQLException e) {
        for (SQLException current = e; current != null; current = current.getNextException()) {
            int code = current.getErrorCode();
            if (code == DEADLOCK || code == LOCK_WAIT_TIMEOUT || code == DUPLICATE_KEY
                    || "40001".equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** A short, jittered pause before the next attempt. */
    static void pause(int attempt) {
        try {
            Thread.sleep(Math.min(200L, 5L << Math.min(attempt, 5)) + ThreadLocalRandom.current().nextLong(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
