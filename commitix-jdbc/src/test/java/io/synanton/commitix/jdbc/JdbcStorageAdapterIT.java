package io.synanton.commitix.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.synanton.commitix.core.port.PayloadSerializer;
import io.synanton.commitix.core.port.StorageAdapter;
import io.synanton.commitix.core.testfixtures.ByteArrayPayloadSerializer;
import io.synanton.commitix.core.testfixtures.StorageAdapterContractTest;
import io.synanton.commitix.jdbc.adapter.out.JdbcStorageAdapter;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs all {@link StorageAdapterContractTest} scenarios against a real PostgreSQL database.
 */
@Testcontainers
class JdbcStorageAdapterIT extends StorageAdapterContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("commitix_test")
            .withUsername("commitix")
            .withPassword("commitix");

    private static HikariDataSource pool;

    @BeforeAll
    static void setUpDatabase() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(10);
        pool = new HikariDataSource(config);

        Flyway.configure()
            .dataSource(pool)
            .locations("classpath:db/migration/commitix")
            .load()
            .migrate();
    }

    @Override
    protected DataSource realDataSource() {
        return pool;
    }

    @Override
    protected PayloadSerializer serializer() {
        return new ByteArrayPayloadSerializer();
    }

    @Override
    protected StorageAdapter storageFor(DataSource dataSource) {
        return new JdbcStorageAdapter(dataSource, serializer());
    }
}
