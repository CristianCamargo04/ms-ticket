package com.ticketing.presentation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RequestContextTest {
    @Test
    void shouldNormalizeRolesWithOrWithoutPrefix() {
        RequestContext context = new RequestContext("admin-1", Set.of("ROLE_ADMIN", "CUSTOMER"), "corr-1", "10.0.0.1");

        assertThat(context.roles()).containsExactlyInAnyOrder("ADMIN", "CUSTOMER");
        assertThat(context.subject()).isEqualTo("admin-1");
        assertThat(context.correlationId()).isEqualTo("corr-1");
        assertThat(context.clientIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void shouldRecognizeAdministrators() {
        assertThat(new RequestContext("a", Set.of("ROLE_ADMIN"), null, null).isAdmin()).isTrue();
        assertThat(new RequestContext("a", Set.of(RequestContext.ROLE_ADMIN), null, null).isAdmin()).isTrue();
        assertThat(new RequestContext("c", Set.of("ROLE_CUSTOMER"), null, null).isAdmin()).isFalse();
    }

    @Test
    void shouldCheckRolesWithOrWithoutPrefix() {
        RequestContext context = new RequestContext("c", Set.of("CUSTOMER"), null, null);

        assertThat(context.hasRole("ROLE_CUSTOMER")).isTrue();
        assertThat(context.hasRole(RequestContext.ROLE_CUSTOMER)).isTrue();
        assertThat(context.hasRole("ADMIN")).isFalse();
        assertThatNullPointerException().isThrownBy(() -> context.hasRole(null));
    }

    @Test
    void shouldTreatMissingRolesAsEmptyAndKeepRolesImmutable() {
        RequestContext withoutRoles = new RequestContext("c", null, null, null);
        Set<String> mutable = new HashSet<>(Set.of("CUSTOMER"));
        RequestContext context = new RequestContext("c", mutable, null, null);
        mutable.add("ADMIN");

        assertThat(withoutRoles.roles()).isEmpty();
        assertThat(withoutRoles.isAdmin()).isFalse();
        assertThat(context.isAdmin()).isFalse();
        assertThatThrownBy(() -> context.roles().add("ADMIN")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRequireSubject() {
        assertThatNullPointerException().isThrownBy(() -> new RequestContext(null, Set.of(), null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new RequestContext(" ", Set.of(), null, null));
    }
}
