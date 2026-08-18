/**
 * Pure domain logic of the Commitix runtime: dispatch and recovery loops.
 *
 * <p>Classes in this package are framework-agnostic and depend only on the core port SPIs.
 * They are invoked by Spring {@code @Scheduled} adapters in {@code adapter/in/schedule/}
 * but can also be called directly in non-Spring environments.
 *
 * <p>{@link io.synanton.commitix.runtime.domain.DispatchLoop} - finds READY intents,
 * claims them, and submits execution to a virtual-thread executor.
 *
 * <p>{@link io.synanton.commitix.runtime.domain.RecoveryLoop} - recovers expired leases,
 * promotes RETRYING intents, and expires overdue intents.
 */
package io.synanton.commitix.runtime.domain;
