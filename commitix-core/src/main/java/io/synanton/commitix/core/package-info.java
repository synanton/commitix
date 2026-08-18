/**
 * Commitix public API.
 *
 * <p>The entry point for application code is {@link io.synanton.commitix.core.Commitix}.
 * Inject it and call {@link io.synanton.commitix.core.Commitix#declare(io.synanton.commitix.core.domain.model.Intent)}
 * inside a transaction to durably commit the intent for execution.
 *
 * <p>This package and its sub-packages carry zero infrastructure dependencies:
 * no JDBC, no Spring, no Jackson. Adapters implementing the port SPIs live in
 * {@code commitix-jdbc} and {@code commitix-runtime}.
 */
package io.synanton.commitix.core;
