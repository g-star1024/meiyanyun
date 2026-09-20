package com.meiyun.finance;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;

@Component
public class ReportPdfBuilder implements ReportBuilder {

    private final ReportDataCollector dataCollector;

    public ReportPdfBuilder(ReportDataCollector dataCollector) {
        this.dataCollector = dataCollector;
    }

    @Override
    public String format() {
        return "PDF";
    }

    @Override
    public ReportBuildResult build(String templateId, String period) {
        ReportDataCollector.ReportData data = dataCollector.collect(templateId, period);
        try {
            BaseFont bf = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font headerFont = new Font(bf, 10, Font.BOLD);
            Font cellFont = new Font(bf, 9, Font.NORMAL);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
            PdfWriter.getInstance(doc, out);
            doc.open();

            PdfPTable table = new PdfPTable(data.headers().size());
            table.setWidthPercentage(100);

            for (String h : data.headers()) {
                PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
                cell.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
                cell.setPadding(4);
                table.addCell(cell);
            }
            for (List<String> row : data.rows()) {
                for (String val : row) {
                    PdfPCell cell = new PdfPCell(new Phrase(val == null ? "" : val, cellFont));
                    cell.setHorizontalAlignment(PdfPCell.ALIGN_LEFT);
                    cell.setPadding(4);
                    table.addCell(cell);
                }
            }
            doc.add(table);
            doc.close();
            return new ReportBuildResult(out.toByteArray(), data.rows().size(), "pdf");
        } catch (Exception e) {
            throw new IllegalStateException("PDF 生成失败: " + e.getMessage(), e);
        }
    }
}
