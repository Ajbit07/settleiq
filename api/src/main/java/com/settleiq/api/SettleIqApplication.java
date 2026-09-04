package com.settleiq.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SettleIQ API.
 *
 * This module owns transport, persistence, scheduling and observability.
 * It owns no arithmetic: every rupee is computed inside settleiq-engine, which
 * has zero runtime dependencies precisely so that a Spring or driver upgrade
 * cannot change a settlement figure.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SettleIqApplication {
    public static void main(String[] args) {
        SpringApplication.run(SettleIqApplication.class, args);
    }
}
