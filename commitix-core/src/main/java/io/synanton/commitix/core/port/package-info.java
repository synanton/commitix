/**
 * Service Provider Interfaces (SPIs) that infrastructure adapters must implement.
 *
 * <p>The core library depends on these interfaces but never on their implementations.
 * Adapters live in separate modules ({@code commitix-jdbc}, {@code commitix-runtime})
 * and are wired at startup by the autoconfiguration or manually by the application.
 *
 * <p>SPIs:
 * <ul>
 *   <li>{@link io.synanton.commitix.core.port.StorageAdapter} - persist and transition intent state.
 *   <li>{@link io.synanton.commitix.core.port.TransactionAdapter} - detect and hook into the ambient tx.
 *   <li>{@link io.synanton.commitix.core.port.ExecutionAdapter} - dispatch to the right handler.
 *   <li>{@link io.synanton.commitix.core.port.IntentHandler} - business handler registered by the application.
 *   <li>{@link io.synanton.commitix.core.port.PayloadSerializer} - convert {@code Payload} ↔ bytes.
 * </ul>
 */
package io.synanton.commitix.core.port;
