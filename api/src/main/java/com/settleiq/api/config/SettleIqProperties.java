package com.settleiq.api.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Operational configuration. Policy thresholds live here rather than in code so
 * they can be tuned per environment, but they are VALIDATED on startup: a
 * misconfigured auto-post limit is a money bug, and the service should refuse
 * to boot rather than run with one.
 */
@Validated
@ConfigurationProperties(prefix = "settleiq")
public record SettleIqProperties(
        @NotBlank String workerId,
        @Min(0) long tolerancePaise,
        @Min(0) long autoPostMaxResiduePaise,
        double autoPostMaxResidueFraction,
        double autoPostMinConfidence,
        @Min(0) long dailyAutoPostBudgetPaise,
        @Min(1) int dailyAutoPostCountBudget,
        @NotBlank String modelPath,
        boolean workerEnabled,
        @Min(1000) long workerPollMs,
        @Min(1) int staleJobMinutes) {

    public SettleIqProperties {
        if (autoPostMinConfidence < 0.5 || autoPostMinConfidence > 1.0)
            throw new IllegalArgumentException(
                "settleiq.auto-post-min-confidence must be in [0.5, 1.0]; got " + autoPostMinConfidence);
        if (autoPostMaxResidueFraction < 0 || autoPostMaxResidueFraction > 0.05)
            throw new IllegalArgumentException(
                "settleiq.auto-post-max-residue-fraction must be in [0, 0.05]; got "
                + autoPostMaxResidueFraction);
    }
}
