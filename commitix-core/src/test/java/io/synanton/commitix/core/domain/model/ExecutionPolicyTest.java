package io.synanton.commitix.core.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExecutionPolicyTest {

    @Test
    void shouldHaveCorrectDefaultPolicy() {
        ExecutionPolicy policy = ExecutionPolicy.defaultPolicy();

        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.failureAction()).isEqualTo(FailureAction.RETRY);
        assertThat(policy.deadline()).isNull();
        assertThat(policy.retryDelay().initial()).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.retryDelay().max()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void shouldReturnFalseForIsUnlimitedOnDefaultPolicy() {
        assertThat(ExecutionPolicy.defaultPolicy().isUnlimited()).isFalse();
    }

    @Test
    void shouldReturnTrueForIsUnlimitedWhenSentinelSet() {
        ExecutionPolicy policy = ExecutionPolicy.defaultPolicy()
            .withMaxAttempts(ExecutionPolicy.UNLIMITED);

        assertThat(policy.isUnlimited()).isTrue();
    }

    @Test
    void shouldReportExhaustedWhenAttemptCountEqualsMaxAttempts() {
        ExecutionPolicy policy = ExecutionPolicy.defaultPolicy();

        assertThat(policy.isExhausted(3)).isTrue();
        assertThat(policy.isExhausted(2)).isFalse();
    }

    @Test
    void shouldNeverReportExhaustedWhenUnlimited() {
        ExecutionPolicy policy = ExecutionPolicy.defaultPolicy()
            .withMaxAttempts(ExecutionPolicy.UNLIMITED);

        assertThat(policy.isExhausted(Integer.MAX_VALUE)).isFalse();
    }

    @Test
    void shouldDetectDeadlineExceeded() {
        Instant deadline = Instant.parse("2026-01-01T00:00:00Z");
        ExecutionPolicy policy = ExecutionPolicy.defaultPolicy().withDeadline(deadline);

        assertThat(policy.isDeadlineExceeded(Instant.parse("2026-01-01T00:00:01Z"))).isTrue();
        assertThat(policy.isDeadlineExceeded(Instant.parse("2025-12-31T23:59:59Z"))).isFalse();
    }

    @Test
    void shouldReturnFalseForDeadlineExceededWhenNoDeadlineSet() {
        assertThat(ExecutionPolicy.defaultPolicy().isDeadlineExceeded(Instant.MAX)).isFalse();
    }

    @Test
    void shouldRejectMaxAttemptsZero() {
        assertThatThrownBy(() -> ExecutionPolicy.defaultPolicy().withMaxAttempts(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportFluencyViaWithers() {
        ExecutionPolicy policy = ExecutionPolicy.defaultPolicy()
            .withMaxAttempts(5)
            .withFailureAction(FailureAction.BLOCK);

        assertThat(policy.maxAttempts()).isEqualTo(5);
        assertThat(policy.failureAction()).isEqualTo(FailureAction.BLOCK);
    }
}
