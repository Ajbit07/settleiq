package com.settleiq.api.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the evaluation artifacts the metrics screen reads.
 *
 * These are produced offline by `make evaluate` against the hidden ground
 * truth, which the running service deliberately cannot see. Exposing them
 * read-only keeps that separation: the API can SHOW how well the engine scored,
 * without ever being able to consult the answers while reconciling.
 *
 * Every file is optional. A missing artifact returns null rather than an error,
 * so a deployment that has not run the evaluator still serves a working UI with
 * those panels absent.
 */
@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics")
public class MetricsController {

    /** Only these names are readable. The path is never taken from the request. */
    private static final List<String> ARTIFACTS =
            List.of("ablation", "calibration", "coverage_risk", "test_report", "full",
                    "ml_ablation", "combined");

    private final Path dir;

    public MetricsController(@Value("${settleiq.metrics-dir:reports/metrics}") String dir) {
        this.dir = Path.of(dir);
    }

    @Operation(summary = "Evaluation artifacts: ablation, calibration, coverage-risk, held-out test")
    @GetMapping
    public Map<String, Object> all() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", Files.isDirectory(dir));
        for (String name : ARTIFACTS) out.put(name, read(name));

        // PROVENANCE. These are stored artifacts from an offline evaluation, not
        // figures from the reconciliation run the operator currently has
        // selected. Without saying so, a screen full of unchanging metrics beside
        // a freshly completed run reads as either stale or dishonest -- and the
        // API is the only thing that knows when the files were actually written.
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("kind", "stored_evaluation_artifacts");
        prov.put("produced_by", "make evaluate / make benchmark, offline against held-out "
                + "ground truth the running service cannot read");
        prov.put("reflects_current_run", false);
        prov.put("directory", dir.toString());
        Map<String, Object> when = new LinkedHashMap<>();
        for (String name : ARTIFACTS) {
            Path p = dir.resolve(name + ".json");
            if (Files.isRegularFile(p)) {
                try {
                    when.put(name, Files.getLastModifiedTime(p).toInstant().toString());
                } catch (Exception ignored) { /* unreadable mtime is not fatal */ }
            }
        }
        prov.put("generated_at", when);
        out.put("provenance", prov);
        return out;
    }

    private Object read(String name) {
        Path p = dir.resolve(name + ".json");
        // Defence in depth: the name is from a fixed list, but a traversal here
        // would expose arbitrary files, so the resolved path is checked anyway.
        if (!p.normalize().startsWith(dir.normalize()) || !Files.isRegularFile(p)) return null;
        try {
            return com.settleiq.Csv.json(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
