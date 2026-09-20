package com.meiyun.finance.storage;

import java.io.InputStream;
import java.net.URL;

public interface StorageService {

    String upload(String bucket, String objectKey, InputStream data, long contentLength, String contentType);

    InputStream download(String bucket, String objectKey);

    void delete(String bucket, String objectKey);

    URL presignedUrl(String bucket, String objectKey, int expireMinutes);
}
