/**
 * Spring Boot autoconfiguration for the Commitix runtime.
 *
 * <p>{@link io.synanton.commitix.runtime.config.CommitixAutoConfiguration} is registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * and activated automatically when {@code commitix.enabled=true} (the default).
 *
 * <p>All beans declared by the autoconfiguration are annotated with
 * {@code @ConditionalOnMissingBean}, so applications can override any individual bean
 * by declaring their own instance in the application context.
 *
 * <p>Configuration is bound via {@link io.synanton.commitix.runtime.config.CommitixProperties}
 * ({@code @ConfigurationProperties("commitix")}). Default values are in
 * {@code commitix-runtime/src/main/resources/application.yml}.
 */
package io.synanton.commitix.runtime.config;
