package com.campus.tracker.application.importing;

import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportJdbcTemplateTest {
    @Test void individualStatementKeepsFiveSecondCapInsideThirtySecondTransaction() throws Exception {
        DataSource source=mock(DataSource.class);ConnectionHolder connection=new ConnectionHolder(mock(Connection.class));connection.setTimeoutInSeconds(30);
        Statement statement=mock(Statement.class);AtomicInteger timeout=new AtomicInteger();
        doAnswer(call->{timeout.set(call.getArgument(0));return null;}).when(statement).setQueryTimeout(anyInt());
        when(statement.getQueryTimeout()).thenAnswer(call->timeout.get());
        TransactionSynchronizationManager.bindResource(source,connection);
        try {new ImportJdbcTemplate(source).applyStatementSettings(statement);assertThat(timeout.get()).isBetween(1,5);}
        finally {TransactionSynchronizationManager.unbindResource(source);}
    }
}
