package com.databuff.apm.common.storage;

import java.sql.SQLException;

/** Fast-fail gate before borrowing a Doris JDBC connection from the pool. */
public interface DorisAccessGate {

    DorisAccessGate ALWAYS_AVAILABLE = () -> { };

    static DorisAccessGate alwaysAvailable() {
        return ALWAYS_AVAILABLE;
    }

    void beforeConnection() throws SQLException;
}
