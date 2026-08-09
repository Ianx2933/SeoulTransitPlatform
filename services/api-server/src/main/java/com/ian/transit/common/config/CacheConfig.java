package com.ian.transit.common.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Spring's cache abstraction.
 *
 * This configuration must stay provider-agnostic: Redis is the default provider
 * for deployment parity, while Caffeine is selected by the local-simple profile.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
