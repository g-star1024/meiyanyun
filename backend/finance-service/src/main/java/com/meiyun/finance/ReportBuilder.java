package com.meiyun.finance;

public interface ReportBuilder {
    String format();
    ReportBuildResult build(String templateId, String period);
}
