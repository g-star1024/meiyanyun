package com.meiyun.finance;

import com.alibaba.excel.EasyExcel;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class ReportXlsxBuilder implements ReportBuilder {

    private final ReportDataCollector dataCollector;

    public ReportXlsxBuilder(ReportDataCollector dataCollector) {
        this.dataCollector = dataCollector;
    }

    @Override
    public String format() {
        return "XLSX";
    }

    @Override
    public ReportBuildResult build(String templateId, String period) {
        ReportDataCollector.ReportData data = dataCollector.collect(templateId, period);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<List<String>> head = new ArrayList<>();
        for (String h : data.headers()) {
            head.add(List.of(h));
        }
        List<List<Object>> sheetData = new ArrayList<>();
        for (List<String> row : data.rows()) {
            sheetData.add(new ArrayList<>(row));
        }
        EasyExcel.write(out).head(head).sheet("报表").doWrite(sheetData);
        return new ReportBuildResult(out.toByteArray(), data.rows().size(), "xlsx");
    }
}
