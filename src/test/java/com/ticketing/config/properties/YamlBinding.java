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

/**
 * Binds the real {@code application.yml} the way Spring Boot does for one active profile: the common document plus
 * the documents whose {@code spring.config.activate.on-profile} matches (later documents override earlier ones,
 * placeholders resolved with their defaults), without starting an application context.
 */
final class YamlBinding {

    static final String APPLICATION_YML = "application.yml";
    private static final String ON_PROFILE = "spring.config.activate.on-profile";

    private YamlBinding() {
    }

    /**
     * Binder over the common document only (no active profile).
     *
     * @return the binder
     */
    static Binder binderFor() {
        return binderForProfile(null);
    }

    /**
     * Binder over the common document plus the documents of a profile.
     *
     * @param profile active profile, {@code null} for none
     * @return the binder
     */
    static Binder binderForProfile(String profile) {
        MutablePropertySources sources = new MutablePropertySources();
        documentsFor(profile).forEach(sources::addFirst);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }

    /**
     * The documents of {@code application.yml} that are active for a profile, in file order.
     *
     * @param profile active profile, {@code null} for none
     * @return the active documents
     */
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
