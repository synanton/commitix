package io.synanton.commitix.runtime.adapter.in.schedule;

import io.synanton.commitix.runtime.domain.RecoveryLoop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Spring-managed scheduler that triggers {@link RecoveryLoop#tick()} at the configured interval.
 */
@Slf4j
@RequiredArgsConstructor
public final class RecoveryScheduler {

    private final RecoveryLoop recoveryLoop;

    @Scheduled(fixedDelayString = "${commitix.recovery.interval}")
    public void recover() {
        try {
            recoveryLoop.tick();
        } catch (Exception ex) {
            log.error("RecoveryLoop tick failed", ex);
        }
    }
}
