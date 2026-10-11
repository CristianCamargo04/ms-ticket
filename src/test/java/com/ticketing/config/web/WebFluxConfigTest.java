package com.ticketing.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.AbstractDataBufferDecoder;
import org.springframework.http.codec.DecoderHttpMessageReader;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.JacksonJsonDecoder;

class WebFluxConfigTest {
    @Test
    void shouldLimitTheBufferedRequestBodySize() {
        ServerCodecConfigurer configurer = ServerCodecConfigurer.create();

        new WebFluxConfig().configureHttpMessageCodecs(configurer);

        List<Integer> limits = configurer.getReaders().stream()
                .filter(DecoderHttpMessageReader.class::isInstance)
                .map(reader -> ((DecoderHttpMessageReader<?>) reader).getDecoder())
                .filter(AbstractDataBufferDecoder.class::isInstance)
                .map(decoder -> ((AbstractDataBufferDecoder<?>) decoder).getMaxInMemorySize())
                .toList();
        assertThat(limits).isNotEmpty().containsOnly(256 * 1024);
        assertThat(configurer.getReaders().stream()
                .filter(DecoderHttpMessageReader.class::isInstance)
                .map(reader -> ((DecoderHttpMessageReader<?>) reader).getDecoder())
                .filter(JacksonJsonDecoder.class::isInstance)
                .map(decoder -> ((JacksonJsonDecoder) decoder).getMaxInMemorySize()))
                .containsExactly(WebFluxConfig.MAX_IN_MEMORY_SIZE);
    }
}
