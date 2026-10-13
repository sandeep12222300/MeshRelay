package io.meshrelay.prototype.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduled maintenance used by the in-memory idempotency cache.
 */
@Configuration
@EnableScheduling
public class AppConfig {
}
