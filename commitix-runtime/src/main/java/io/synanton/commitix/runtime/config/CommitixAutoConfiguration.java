package io.synanton.commitix.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.synanton.commitix.core.Commitix;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.internal.TransactionalCommitix;
import io.synanton.commitix.core.port.ExecutionAdapter;
import io.synanton.commitix.core.port.IntentHandler;
import io.synanton.commitix.core.port.PayloadSerializer;
import io.synanton.commitix.core.port.StorageAdapter;
import io.synanton.commitix.core.port.TransactionAdapter;
import io.synanton.commitix.jdbc.adapter.out.JdbcStorageAdapter;
import io.synanton.commitix.runtime.adapter.in.schedule.DispatcherScheduler;
import io.synanton.commitix.runtime.adapter.in.schedule.RecoveryScheduler;
import io.synanton.commitix.runtime.adapter.out.JacksonPayloadSerializer;
import io.synanton.commitix.runtime.adapter.out.JvmExecutionAdapter;
import io.synanton.commitix.runtime.adapter.out.SpringJdbcTransactionAdapter;
import io.synanton.commitix.runtime.domain.DispatchLoop;
import io.synanton.commitix.runtime.domain.RecoveryLoop;
import io.synanton.commitix.runtime.domain.WorkerId;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot autoconfiguration for the Commitix runtime.
 *
 * <p>Activated when {@code commitix.enabled=true} (default).
 * Any bean declared here can be overridden by the application by providing its own instance.
 */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(CommitixProperties.class)
@ConditionalOnProperty(name = "commitix.enabled", havingValue = "true", matchIfMissing = true)
public class CommitixAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock commitixClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(PayloadSerializer.class)
    public PayloadSerializer commitixPayloadSerializer(ObjectMapper objectMapper) {
        return new JacksonPayloadSerializer(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(StorageAdapter.class)
    public StorageAdapter commitixStorageAdapter(DataSource dataSource, PayloadSerializer payloadSerializer) {
        return new JdbcStorageAdapter(dataSource, payloadSerializer);
    }

    @Bean
    @ConditionalOnMissingBean(TransactionAdapter.class)
    public TransactionAdapter commitixTransactionAdapter() {
        return new SpringJdbcTransactionAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionAdapter.class)
    public ExecutionAdapter commitixExecutionAdapter(List<IntentHandler> handlers) {
        Map<String, IntentHandler> handlerMap = handlers.stream()
            .collect(Collectors.toMap(
                h -> handlerKey(h),
                Function.identity()
            ));
        return new JvmExecutionAdapter(handlerMap);
    }

    @Bean
    @ConditionalOnMissingBean(Commitix.class)
    public Commitix commitix(StorageAdapter storage, TransactionAdapter transaction) {
        return new TransactionalCommitix(storage, transaction);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "commitixVirtualThreadExecutor")
    public ExecutorService commitixVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(DispatchLoop.class)
    public DispatchLoop commitixDispatchLoop(StorageAdapter storage,
                                             ExecutionAdapter execution,
                                             ExecutorService commitixVirtualThreadExecutor,
                                             Clock commitixClock,
                                             CommitixProperties properties) {
        String workerId = WorkerId.generate(properties.getWorkerIdPrefix());
        return new DispatchLoop(
            storage,
            execution,
            commitixVirtualThreadExecutor,
            commitixClock,
            workerId,
            properties.getDispatcher().getBatchSize(),
            properties.getDispatcher().getLeaseDuration()
        );
    }

    @Bean
    @ConditionalOnMissingBean(RecoveryLoop.class)
    public RecoveryLoop commitixRecoveryLoop(StorageAdapter storage) {
        return new RecoveryLoop(storage);
    }

    @Bean
    @ConditionalOnMissingBean(DispatcherScheduler.class)
    public DispatcherScheduler commitixDispatcherScheduler(DispatchLoop dispatchLoop) {
        return new DispatcherScheduler(dispatchLoop);
    }

    @Bean
    @ConditionalOnMissingBean(RecoveryScheduler.class)
    public RecoveryScheduler commitixRecoveryScheduler(RecoveryLoop recoveryLoop) {
        return new RecoveryScheduler(recoveryLoop);
    }

    private static String handlerKey(IntentHandler handler) {
        // Handlers must be annotated or implement a getOperationKey() contract.
        // Convention: handler class annotated with @CommitixHandler or implements OperationKeyProvider.
        if (handler instanceof OperationKeyProvider keyProvider) {
            return keyProvider.operationKey();
        }
        throw new IllegalArgumentException(
            "IntentHandler " + handler.getClass().getName()
            + " must implement OperationKeyProvider or be annotated with @CommitixHandler"
        );
    }

    /**
     * Marker interface that {@link IntentHandler} implementations can implement
     * to declare their operation key ({@code operationId@version}).
     */
    public interface OperationKeyProvider {
        String operationKey();
    }

    // Convenience: handlers can use this to build the key
    public static String operationKey(Intent intent) {
        return JvmExecutionAdapter.handlerKey(intent);
    }
}
