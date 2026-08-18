/**
 * Sealed exception hierarchy for Commitix.
 *
 * <p>All exceptions extend {@link io.synanton.commitix.core.domain.error.CommitixException}
 * (a {@link RuntimeException}) so callers are not forced to declare checked exceptions.
 * The sealed hierarchy makes exhaustive pattern matching possible in Java 21+.
 *
 * <p>Exception types by origin:
 * <ul>
 *   <li>{@link io.synanton.commitix.core.domain.error.NoActiveTransactionException} -
 *       thrown by {@code Commitix.declare} when no transaction is active.
 *   <li>{@link io.synanton.commitix.core.domain.error.StorageException} -
 *       wraps a JDBC {@code SQLException} from the storage adapter.
 *   <li>{@link io.synanton.commitix.core.domain.error.SerializationException} -
 *       thrown by the payload serializer on encode/decode failure.
 *   <li>{@link io.synanton.commitix.core.domain.error.ConcurrentUpdateException} -
 *       thrown by the runtime when an expected CAS succeeds but returns false unexpectedly.
 *   <li>{@link io.synanton.commitix.core.domain.error.IntentExecutionException} -
 *       thrown by the execution adapter for infrastructure-level (non-business) failures.
 * </ul>
 */
package io.synanton.commitix.core.domain.error;
