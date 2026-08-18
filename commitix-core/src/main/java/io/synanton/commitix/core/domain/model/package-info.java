/**
 * Immutable domain model for the Commitix durable execution intent.
 *
 * <p>All types in this package are Java records or enums - value objects with no identity
 * beyond their field values. Once an {@link io.synanton.commitix.core.domain.model.Intent}
 * is persisted, its fields never change; only the lifecycle state tracked by
 * {@link io.synanton.commitix.core.domain.model.StoredIntent} evolves.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link io.synanton.commitix.core.domain.model.Intent} - the declaration of work to do.
 *   <li>{@link io.synanton.commitix.core.domain.model.StoredIntent} - intent + all storage state
 *       ({@code lease_generation}, {@code version}, {@code attempt_count}, …).
 *   <li>{@link io.synanton.commitix.core.domain.model.ExecutionPolicy} - retry, deadline, failure
 *       action configuration.
 *   <li>{@link io.synanton.commitix.core.domain.model.IntentStatus} - the lifecycle state machine.
 * </ul>
 */
package io.synanton.commitix.core.domain.model;
