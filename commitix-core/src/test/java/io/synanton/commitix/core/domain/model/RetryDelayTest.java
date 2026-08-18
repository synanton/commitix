package io.synanton.commitix.core.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryDelayTest {

    @Test
    void shouldReturnInitialDelayForFirstRetry() {
        RetryDelay delay = RetryDelay.exponential(Duration.ofSeconds(1), Duration.ofMinutes(1));

        assertThat(delay.nextDelay(0)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void shouldDoubleDelayOnEachRetryWithDefaultMultiplier() {
        RetryDelay delay = RetryDelay.exponential(Duration.ofSeconds(1), Duration.ofMinutes(1));

        assertThat(delay.nextDelay(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(delay.nextDelay(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(delay.nextDelay(3)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void shouldClampToMaxDelay() {
        RetryDelay delay = RetryDelay.exponential(Duration.ofSeconds(1), Duration.ofSeconds(5));

        assertThat(delay.nextDelay(10)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void shouldReturnConstantDelayForAllRetries() {
        RetryDelay delay = RetryDelay.constant(Duration.ofSeconds(3));

        assertThat(delay.nextDelay(0)).isEqualTo(Duration.ofSeconds(3));
        assertThat(delay.nextDelay(5)).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void shouldRejectNonPositiveInitialDelay() {
        assertThatThrownBy(() -> RetryDelay.exponential(Duration.ZERO, Duration.ofMinutes(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectMaxSmallerThanInitial() {
        assertThatThrownBy(
            () -> new RetryDelay(Duration.ofSeconds(10), Duration.ofSeconds(1), 2.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectMultiplierLessThanOne() {
        assertThatThrownBy(
            () -> new RetryDelay(Duration.ofSeconds(1), Duration.ofMinutes(1), 0.5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportWitherForFluency() {
        RetryDelay original = RetryDelay.exponential(Duration.ofSeconds(1), Duration.ofMinutes(1));
        RetryDelay updated = original.withMultiplier(3.0);

        assertThat(updated.nextDelay(1)).isEqualTo(Duration.ofSeconds(3));
        assertThat(original.nextDelay(1)).isEqualTo(Duration.ofSeconds(2));
    }
}
