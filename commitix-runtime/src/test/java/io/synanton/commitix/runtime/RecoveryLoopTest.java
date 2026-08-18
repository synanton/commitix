package io.synanton.commitix.runtime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.synanton.commitix.core.port.StorageAdapter;
import io.synanton.commitix.runtime.domain.RecoveryLoop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecoveryLoopTest {

    @Mock
    private StorageAdapter storage;

    private RecoveryLoop loop;

    @BeforeEach
    void setUp() {
        loop = new RecoveryLoop(storage);
    }

    @Test
    void shouldCallAllThreeRecoveryOperationsOnTick() {
        when(storage.recoverExpiredLeases()).thenReturn(0);
        when(storage.promoteRetryingIntents()).thenReturn(0);
        when(storage.expireOverdueIntents()).thenReturn(0);

        loop.tick();

        verify(storage).recoverExpiredLeases();
        verify(storage).promoteRetryingIntents();
        verify(storage).expireOverdueIntents();
    }

    @Test
    void shouldHandleNonZeroRecoveryCounts() {
        when(storage.recoverExpiredLeases()).thenReturn(3);
        when(storage.promoteRetryingIntents()).thenReturn(2);
        when(storage.expireOverdueIntents()).thenReturn(1);

        loop.tick();

        verify(storage).recoverExpiredLeases();
        verify(storage).promoteRetryingIntents();
        verify(storage).expireOverdueIntents();
    }
}
