package com.settleiq;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal RFC4180-ish CSV reader/writer and a tiny JSON reader. Zero deps. */
public final class Csv {
    private Csv() {}

    public static List<Map<String, String>> read(Path p) {
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            List<Map<String, String>> out = new ArrayList<>();
            if (lines.isEmpty()) return out;
            List<String> header = splitLine(lines.get(0));
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isEmpty()) continue;
                List<String> cells = splitLine(lines.get(i));
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++)
                    row.put(header.get(c), c < cells.size() ? cells.get(c) : "");
                out.add(row);
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("reading " + p, e);
        }
    }

    static List<String> splitLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else q = false;
                } else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    public static String esc(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0)
            return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    public static void write(Path p, List<String> header, List<List<String>> rows) {
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                w.write(String.join(",", header));
                w.write("\n");
                for (List<String> r : rows) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < r.size(); i++) {
                        if (i > 0) sb.append(',');
                        sb.append(esc(r.get(i)));
                    }
                    w.write(sb.toString());
                    w.write("\n");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("writing " + p, e);
        }
    }

    // ------------------------------------------------------------------ JSON
    /** Tiny recursive-descent JSON parser. Returns Map/List/String/Double/Boolean/null. */
    public static Object json(String s) { return new J(s).value(); }

    public static Object jsonFile(Path p) {
        try { return json(Files.readString(p, StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new RuntimeException("reading " + p, e); }
    }

    static final class J {
        private final String s; private int i;
        J(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> str();
                case 't' -> { i += 4; yield Boolean.TRUE; }
                case 'f' -> { i += 5; yield Boolean.FALSE; }
                case 'n' -> { i += 4; yield null; }
                default -> num();
            };
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = str(); ws(); i++; // colon
                m.put(k, value()); ws();
                if (s.charAt(i) == ',') { i++; continue; }
                i++; return m;
            }
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value()); ws();
                if (s.charAt(i) == ',') { i++; continue; }
                i++; return l;
            }
        }
        String str() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (s.charAt(i) != '"') {
                char c = s.charAt(i);
                if (c == '\\') {
                    i++;
                    char e = s.charAt(i);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else sb.append(c);
                i++;
            }
            i++;
            return sb.toString();
        }
        Object num() {
            int st = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }
    }

    /** Minimal JSON writer for API responses. */
    public static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }
}
