package com.ticketing.config.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * WebFlux server settings: request bodies are buffered up to {@value #MAX_IN_MEMORY_SIZE} bytes (every API body is a
 * few hundred bytes), so oversized payloads are rejected with {@code 413} instead of consuming memory.
 */
@Configuration(proxyBeanMethods = false)
public class WebFluxConfig implements WebFluxConfigurer {

    /** Maximum bytes buffered when decoding a request body (256 KB). */
    public static final int MAX_IN_MEMORY_SIZE = 256 * 1024;

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE);
    }
}
