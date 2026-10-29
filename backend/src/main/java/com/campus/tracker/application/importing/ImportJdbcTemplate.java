package com.campus.tracker.application.importing;

import javax.sql.DataSource;
import java.sql.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring's transaction deadline must not widen an individual import database statement beyond five seconds. */
final class ImportJdbcTemplate extends JdbcTemplate {
    ImportJdbcTemplate(DataSource source) { super(source);setQueryTimeout(5); }
    @Override protected void applyStatementSettings(Statement statement) throws SQLException {
        super.applyStatementSettings(statement);
        int transactionSeconds=statement.getQueryTimeout();
        statement.setQueryTimeout(transactionSeconds>0?Math.min(transactionSeconds,5):5);
    }
}
