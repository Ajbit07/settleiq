package com.settleiq.api.web;

import com.settleiq.api.service.CsvIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Upload surface.
 *
 * One file, one entity, one ingestion run. Deliberately not a "load everything"
 * endpoint: a single call that ingests seven files has one status code for
 * seven independent outcomes, and the caller cannot tell which half landed.
 *
 * The response is the ingestion report itself -- counts, reasons and a sample
 * of the actual rejected lines -- because a bare 200 tells an operator nothing
 * about whether their file was understood.
 */
@RestController
@RequestMapping("/api/v1/ingest")
@Tag(name = "Ingestion")
public class IngestController {

    private final CsvIngestService svc;
    private final JdbcTemplate jdbc;

    public IngestController(CsvIngestService svc, JdbcTemplate jdbc) {
        this.svc = svc;
        this.jdbc = jdbc;
    }

    @Operation(summary = "Upload one CSV of one entity type for one merchant")
    @PostMapping(consumes = "multipart/form-data")
    public Map<String, Object> upload(@RequestParam("merchantId") String merchantId,
                                      @RequestParam("entity") String entity,
                                      @RequestPart("file") MultipartFile file) throws IOException {
        var e = CsvIngestService.Entity.of(entity);
        String name = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        var r = svc.ingest(merchantId, e, name, file.getBytes());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ingestion_id", r.ingestionId());
        m.put("merchant_id", r.merchantId());
        m.put("entity", r.entity());
        m.put("source_name", r.sourceName());
        m.put("rows_seen", r.rowsSeen());
        m.put("rows_accepted", r.rowsAccepted());
        m.put("rows_rejected", r.rowsRejected());
        m.put("rejects_by_reason", r.rejectsByReason());
        m.put("sample_rejects", r.sample().stream().map(x -> Map.of(
                "line_no", x.lineNo(), "reason_code", x.reasonCode(),
                "detail", x.detail(), "raw_line", x.rawLine())).toList());
        m.put("state", r.state());
        return m;
    }

    @Operation(summary = "Ingestion history for a merchant")
    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam("merchantId") String merchantId,
                                          @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return jdbc.queryForList("""
                SELECT ingestion_id, merchant_id, entity, source_name, source_bytes,
                       source_sha256, rows_seen, rows_accepted, rows_rejected,
                       state, error, started_at, finished_at
                  FROM ingestion_run WHERE merchant_id = ?
                 ORDER BY ingestion_id DESC LIMIT ?
                """, merchantId, Math.min(Math.max(limit, 1), 200));
    }

    @Operation(summary = "The actual rejected rows for one ingestion run")
    @GetMapping("/runs/{ingestionId}/rejects")
    public List<Map<String, Object>> rejects(@PathVariable("ingestionId") long ingestionId,
                                             @RequestParam("merchantId") String merchantId,
                                             @RequestParam(name = "limit", defaultValue = "200") int limit) {
        // Scoped by merchant as well as run id: a run id alone would let one
        // tenant read another's rejected rows, which contain raw source data.
        Integer own = jdbc.queryForObject(
                "SELECT count(*) FROM ingestion_run WHERE ingestion_id=? AND merchant_id=?",
                Integer.class, ingestionId, merchantId);
        if (own == null || own == 0)
            throw new NotFoundException("no ingestion run " + ingestionId + " for that merchant");
        return jdbc.queryForList("""
                SELECT line_no, reason_code, detail, raw_line
                  FROM ingestion_reject WHERE ingestion_id = ?
                 ORDER BY line_no LIMIT ?
                """, ingestionId, Math.min(Math.max(limit, 1), 1000));
    }

    /** The header each entity requires, so a client can show it before uploading. */
    @GetMapping("/schema")
    public List<Map<String, String>> schema() {
        return java.util.Arrays.stream(CsvIngestService.Entity.values())
                .map(e -> Map.of("entity", e.name(), "table", e.table, "header", e.header))
                .toList();
    }
}
