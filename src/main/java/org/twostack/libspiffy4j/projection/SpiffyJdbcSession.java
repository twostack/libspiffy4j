package org.twostack.libspiffy4j.projection;

import org.apache.pekko.japi.function.Function;
import org.apache.pekko.projection.jdbc.JdbcSession;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class SpiffyJdbcSession implements JdbcSession {

    private final Connection connection;

    public SpiffyJdbcSession(DataSource dataSource) {
        try {
            this.connection = dataSource.getConnection();
            this.connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create JDBC session", e);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public <Result> Result withConnection(Function<Connection, Result> func) throws Exception {
        return func.apply(connection);
    }

    @Override
    public void commit() throws SQLException {
        connection.commit();
    }

    @Override
    public void rollback() throws SQLException {
        connection.rollback();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
