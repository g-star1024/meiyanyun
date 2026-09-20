package com.meiyun.finance;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReportBuilderRegistry {

    private final Map<String, ReportBuilder> map = new HashMap<>();

    public ReportBuilderRegistry(List<ReportBuilder> builders) {
        for (ReportBuilder b : builders) {
            map.put(b.format().toUpperCase(), b);
        }
    }

    public ReportBuilder forFormat(String format) {
        ReportBuilder b = map.get(format.toUpperCase());
        if (b == null) {
            throw new IllegalArgumentException("unsupported format: " + format);
        }
        return b;
    }
}
