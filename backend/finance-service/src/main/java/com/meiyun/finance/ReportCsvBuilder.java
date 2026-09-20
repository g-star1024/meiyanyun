package com.meiyun.finance;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ReportCsvBuilder implements ReportBuilder {

    public record CsvData(List<String> headers, List<List<String>> rows, byte[] content) {}

    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ReportDataCollector dataCollector;

    public ReportCsvBuilder(ReportDataCollector dataCollector) {
        this.dataCollector = dataCollector;
    }

    @Override
    public String format() {
        return "CSV";
    }

    @Override
    public ReportBuildResult build(String templateId, String period) {
        ReportDataCollector.ReportData data = dataCollector.collect(templateId, period);
        byte[] content = csvBytes(data.headers(), data.rows());
        return new ReportBuildResult(content, data.rows().size(), "csv");
    }

    public CsvData buildCsvData(String templateId, String period) {
        ReportDataCollector.ReportData data = dataCollector.collect(templateId, period);
        return new CsvData(data.headers(), data.rows(), csvBytes(data.headers(), data.rows()));
    }

    private byte[] csvBytes(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        row(sb, headers.toArray());
        for (List<String> r : rows) {
            row(sb, r.toArray());
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[3 + body.length];
        all[0] = (byte) 0xEF;
        all[1] = (byte) 0xBB;
        all[2] = (byte) 0xBF;
        System.arraycopy(body, 0, all, 3, body.length);
        return all;
    }

    private void row(StringBuilder sb, Object... vals) {
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(esc(vals[i]));
        }
        sb.append("\r\n");
    }

    private String esc(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static boolean hashEquals(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null
                || expectedHex.length() != actualHex.length()) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                actualHex.getBytes(StandardCharsets.UTF_8));
    }

    static String downloadFileName(String templateName, String period, OffsetDateTime createdAt, String format) {
        String ext = switch (format.toUpperCase()) {
            case "XLSX" -> ".xlsx";
            case "PDF" -> ".pdf";
            default -> ".csv";
        };
        String ts = createdAt.atZoneSameInstant(CN_ZONE).format(FILE_TS);
        return templateName + "-" + period + "-" + ts + ext;
    }
}
