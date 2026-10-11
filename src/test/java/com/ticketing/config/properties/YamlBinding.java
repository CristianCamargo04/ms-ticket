package com.ticketing.config.properties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

final class YamlBinding {
    static final String APPLICATION_YML = "application.yml";
    private static final String ON_PROFILE = "spring.config.activate.on-profile";

    private YamlBinding() {
    }

    static Binder binderFor() {
        return binderForProfile(null);
    }

    static Binder binderForProfile(String profile) {
        MutablePropertySources sources = new MutablePropertySources();
        documentsFor(profile).forEach(sources::addFirst);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }

    static List<PropertySource<?>> documentsFor(String profile) {
        try {
            List<PropertySource<?>> active = new ArrayList<>();
            for (PropertySource<?> document : new YamlPropertySourceLoader()
                    .load(APPLICATION_YML, new ClassPathResource(APPLICATION_YML))) {
                Object onProfile = document.getProperty(ON_PROFILE);
                if (onProfile == null || onProfile.toString().equals(profile)) {
                    active.add(document);
                }
            }
            return active;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
