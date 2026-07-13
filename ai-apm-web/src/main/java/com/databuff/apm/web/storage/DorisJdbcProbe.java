package com.databuff.apm.web.storage;

import com.databuff.apm.common.storage.DorisConnectionConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

final class DorisJdbcProbe {

    private DorisJdbcProbe() {
    }

    static boolean ping(
            DorisConnectionConfig config,
            String username,
            String password,
            int connectTimeoutMs,
            int socketTimeoutMs) {
        String url = config.jdbcUrl("")
                + "&connectTimeout=" + Math.max(500, connectTimeoutMs)
                + "&socketTimeout=" + Math.max(500, socketTimeoutMs);
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.max(1, socketTimeoutMs / 1000));
            statement.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
