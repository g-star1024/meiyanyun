package com.meiyun.finance;

public record ReportBuildResult(byte[] content, int rowCount, String fileExtension) {}
