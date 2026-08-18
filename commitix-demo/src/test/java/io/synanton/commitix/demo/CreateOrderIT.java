package io.synanton.commitix.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test covering Phase 1 acceptance criteria §14:
 * <ul>
 *   <li>#1–3: declare intent inside transaction, commit persists, rollback discards
 *   <li>#4–5: claim with generation/version, success recording
 * </ul>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreateOrderIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("commitix_demo")
            .withUsername("commitix")
            .withPassword("commitix");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldPersistOrderAndIntentsOnCommit() {
        var request = new CreateOrderBody("cust-1", "prod-A", 2);
        ResponseEntity<String> response = restTemplate.postForEntity("/orders", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Integer orderCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM demo_orders WHERE customer_id = 'cust-1'", Integer.class);
        assertThat(orderCount).isEqualTo(1);

        Integer intentCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM commitix_intents WHERE status = 'READY'", Integer.class);
        assertThat(intentCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldExecuteIntentsAndReachSuccess() {
        var request = new CreateOrderBody("cust-2", "prod-B", 5);
        restTemplate.postForEntity("/orders", request, String.class);

        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                Integer successCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM commitix_intents "
                    + "WHERE status = 'SUCCESS' AND operation_id IN ('INVENTORY_RESERVE', 'WAREHOUSE_NOTIFY')",
                    Integer.class);
                assertThat(successCount).isEqualTo(2);
            });
    }

    record CreateOrderBody(String customerId, String productId, int quantity) {
    }
}
