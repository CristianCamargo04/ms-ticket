package com.ticketing.config.properties;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds the real {@code application*.yml} files the way Spring Boot does (later files override earlier ones,
 * placeholders resolved with their defaults), without starting an application context.
 */
final class YamlBinding {

    private YamlBinding() {
    }

    static Binder binderFor(String... classpathResources) {
        MutablePropertySources sources = new MutablePropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : classpathResources) {
            try {
                for (PropertySource<?> source : loader.load(resource, new ClassPathResource(resource))) {
                    sources.addFirst(source);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }
}
