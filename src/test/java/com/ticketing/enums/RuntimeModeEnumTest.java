package com.ticketing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RuntimeModeEnumTest {
    @ParameterizedTest(name = "{0}: api={1} worker={2}")
    @CsvSource({
            "ALL,    true,  true",
            "API,    true,  false",
            "WORKER, false, true"
    })
    void shouldEnableRolesPerMode(RuntimeModeEnum mode, boolean api, boolean worker) {
        assertThat(mode.apiEnabled()).isEqualTo(api);
        assertThat(mode.workerEnabled()).isEqualTo(worker);
    }
}
