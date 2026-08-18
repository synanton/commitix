package io.synanton.commitix.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.synanton.commitix.core.domain.error.NoActiveTransactionException;
import io.synanton.commitix.jdbc.adapter.out.ManualJdbcTransactionAdapter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManualJdbcTransactionAdapterTest {

    private ManualJdbcTransactionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ManualJdbcTransactionAdapter();
    }

    @Test
    void shouldReportNoActiveTransactionBeforeBegin() {
        assertThat(adapter.isTransactionActive()).isFalse();
    }

    @Test
    void shouldReportActiveTransactionAfterBegin() throws Exception {
        adapter.begin(mock(Connection.class));
        assertThat(adapter.isTransactionActive()).isTrue();
    }

    @Test
    void shouldReportInactiveTransactionAfterCommit() throws Exception {
        Connection connection = mock(Connection.class);
        adapter.begin(connection);
        adapter.commit();
        assertThat(adapter.isTransactionActive()).isFalse();
    }

    @Test
    void shouldReportInactiveTransactionAfterRollback() throws Exception {
        Connection connection = mock(Connection.class);
        adapter.begin(connection);
        adapter.rollback();
        assertThat(adapter.isTransactionActive()).isFalse();
    }

    @Test
    void shouldCommitUnderlyingConnection() throws Exception {
        Connection connection = mock(Connection.class);
        adapter.begin(connection);
        adapter.commit();
        verify(connection).commit();
    }

    @Test
    void shouldRollbackUnderlyingConnection() throws Exception {
        Connection connection = mock(Connection.class);
        adapter.begin(connection);
        adapter.rollback();
        verify(connection).rollback();
    }

    @Test
    void shouldFireAfterCommitCallbacksInOrder() throws Exception {
        Connection connection = mock(Connection.class);
        adapter.begin(connection);

        List<String> fired = new ArrayList<>();
        adapter.afterCommit(() -> fired.add("first"));
        adapter.afterCommit(() -> fired.add("second"));

        adapter.commit();
        assertThat(fired).containsExactly("first", "second");
    }

    @Test
    void shouldNotFireAfterCommitCallbacksOnRollback() throws Exception {
        Connection connection = mock(Connection.class);
        adapter.begin(connection);

        List<String> fired = new ArrayList<>();
        adapter.afterCommit(() -> fired.add("callback"));
        adapter.rollback();

        assertThat(fired).isEmpty();
    }

    @Test
    void shouldContinueFiringCallbacksIfOneThrows() throws Exception {
        Connection connection = mock(Connection.class);
        adapter.begin(connection);

        List<String> fired = new ArrayList<>();
        adapter.afterCommit(() -> { throw new RuntimeException("boom"); });
        adapter.afterCommit(() -> fired.add("second"));

        adapter.commit();
        assertThat(fired).containsExactly("second");
    }

    @Test
    void shouldThrowWhenBeginCalledTwice() throws Exception {
        adapter.begin(mock(Connection.class));
        assertThatThrownBy(() -> adapter.begin(mock(Connection.class)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldThrowOnAfterCommitWithNoActiveTransaction() {
        assertThatThrownBy(() -> adapter.afterCommit(() -> {}))
            .isInstanceOf(NoActiveTransactionException.class);
    }

    @Test
    void shouldThrowOnCurrentConnectionWithNoActiveTransaction() {
        assertThatThrownBy(() -> adapter.currentConnection())
            .isInstanceOf(NoActiveTransactionException.class);
    }

    @Test
    void shouldReturnRegisteredConnection() throws Exception {
        Connection connection = mock(Connection.class);
        adapter.begin(connection);
        assertThat(adapter.currentConnection()).isSameAs(connection);
    }

    @Test
    void shouldBeReusableAfterCommit() throws Exception {
        Connection first = mock(Connection.class);
        adapter.begin(first);
        adapter.commit();

        Connection second = mock(Connection.class);
        adapter.begin(second);
        assertThat(adapter.currentConnection()).isSameAs(second);
    }

    @Test
    void shouldRollbackNoOpWhenNoTransactionActive() {
        adapter.rollback();
        assertThat(adapter.isTransactionActive()).isFalse();
    }

    @Test
    void shouldThrowStorageExceptionWhenRollbackFails() throws Exception {
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("connection lost")).when(connection).rollback();
        adapter.begin(connection);
        assertThatThrownBy(() -> adapter.rollback())
            .hasMessageContaining("Failed to rollback");
    }
}
