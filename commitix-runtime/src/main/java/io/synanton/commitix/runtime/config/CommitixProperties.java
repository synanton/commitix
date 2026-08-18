package io.synanton.commitix.runtime.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the Commitix runtime.
 * All defaults are defined in {@code commitix-runtime/src/main/resources/application.yml}.
 */
@Data
@ConfigurationProperties(prefix = "commitix")
public final class CommitixProperties {

    private boolean enabled = true;
    private String workerIdPrefix = "commitix";

    private Dispatcher dispatcher = new Dispatcher();
    private Recovery recovery = new Recovery();

    @Data
    public static final class Dispatcher {
        private Duration interval;
        private int batchSize;
        private Duration leaseDuration;
    }

    @Data
    public static final class Recovery {
        private Duration interval;
        private int batchSize;
    }
}
