package com.ticketing.notification.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Prod-only startup check (ADR-017): strictly resolves every property from the application
 * config files before any bean is created, so a missing environment variable such as
 * DB_PASSWORD stops startup with "Could not resolve placeholder 'DB_PASSWORD'".
 *
 * Needed because Spring Boot's property binding leaves unresolvable placeholders in place
 * instead of failing: without this check the literal "${DB_PASSWORD}" is sent as the password
 * and startup fails later with a misleading authentication error — or, for hosts like Redis
 * or Kafka, not at startup at all.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class RequiredConfigurationCheck {

    @Bean
    static BeanFactoryPostProcessor requiredConfigurationValidator(ConfigurableEnvironment environment) {
        return beanFactory -> {
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (source instanceof OriginTrackedMapPropertySource configFile) {
                    for (String name : configFile.getPropertyNames()) {
                        environment.getProperty(name);
                    }
                }
            }
        };
    }
}
