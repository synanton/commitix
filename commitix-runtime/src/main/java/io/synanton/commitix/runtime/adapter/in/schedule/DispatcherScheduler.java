package io.synanton.commitix.runtime.adapter.in.schedule;

import io.synanton.commitix.runtime.domain.DispatchLoop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Spring-managed scheduler that triggers {@link DispatchLoop#tick()} at the configured interval.
 */
@Slf4j
@RequiredArgsConstructor
public final class DispatcherScheduler {

    private final DispatchLoop dispatchLoop;

    @Scheduled(fixedDelayString = "${commitix.dispatcher.interval}")
    public void dispatch() {
        try {
            dispatchLoop.tick();
        } catch (Exception ex) {
            log.error("DispatchLoop tick failed", ex);
        }
    }
}
