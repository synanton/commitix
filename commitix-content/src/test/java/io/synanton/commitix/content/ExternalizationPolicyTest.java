package io.synanton.commitix.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.synanton.commitix.content.ExternalizationPolicy.Decision;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExternalizationPolicyTest {

    @Test
    void belowThresholdStaysInline() {
        ExternalizationPolicy policy =
                ExternalizationPolicy.disabledByDefault(1024).build();
        assertThat(policy.decide("acme", "OrderPayload", 512)).isEqualTo(Decision.INLINE);
    }

    @Test
    void atThresholdExternalizes() {
        ExternalizationPolicy policy =
                ExternalizationPolicy.disabledByDefault(1024).build();
        assertThat(policy.decide("acme", "OrderPayload", 1024)).isEqualTo(Decision.EXTERNAL);
    }

    @Test
    void killSwitchVetoesEverything() {
        ExternalizationPolicy policy = ExternalizationPolicy.disabledByDefault(1)
                .killSwitch(true)
                .build();
        assertThat(policy.decide("acme", "OrderPayload", 10_000_000)).isEqualTo(Decision.INLINE);
    }

    @Test
    void tenantVetoWinsOverThreshold() {
        ExternalizationPolicy policy = ExternalizationPolicy.disabledByDefault(1)
                .disabledTenants(Set.of("acme"))
                .build();
        assertThat(policy.decide("acme", "OrderPayload", 10_000)).isEqualTo(Decision.INLINE);
        assertThat(policy.decide("other", "OrderPayload", 10_000)).isEqualTo(Decision.EXTERNAL);
    }

    @Test
    void intentTypeVetoWinsOverThreshold() {
        ExternalizationPolicy policy = ExternalizationPolicy.disabledByDefault(1)
                .disabledIntentTypes(Set.of("SmallPayload"))
                .build();
        assertThat(policy.decide("acme", "SmallPayload", 10_000)).isEqualTo(Decision.INLINE);
        assertThat(policy.decide("acme", "LargePayload", 10_000)).isEqualTo(Decision.EXTERNAL);
    }

    @Test
    void inlineOnlyKeepsEverythingInline() {
        ExternalizationPolicy policy = ExternalizationPolicy.inlineOnly();
        assertThat(policy.decide("acme", "OrderPayload", Long.MAX_VALUE - 1))
                .isEqualTo(Decision.INLINE);
    }

    @Test
    void rejectsBlankTenantAndNegativeSize() {
        ExternalizationPolicy policy =
                ExternalizationPolicy.disabledByDefault(1024).build();
        assertThatThrownBy(() -> policy.decide("", "OrderPayload", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.decide("acme", "OrderPayload", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
