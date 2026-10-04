package com.meiyun.marketing.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * MinIO/S3 直连存储（棒⑧卡4）。普通类非 Spring bean：
 * 由 DelegatingStorageService 门面按目录快照启用时懒装配（config hash 变化即重建），
 * 未启用时 classpath 中 MinioClient 完全不初始化，local 兜底零影响。
 */
public class S3StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final MinioClient client;

    public S3StorageService(String endpoint, String region, String accessKey, String secretKey) {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey);
        if (region != null && !region.isBlank()) {
            builder.region(region);
        }
        this.client = builder.build();
    }

    @Override
    public String upload(String bucket, String objectKey, InputStream data, long contentLength, String contentType) {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("对象存储 bucket 已自建 bucket={}", bucket);
            }
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(data, contentLength, -1)
                    .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                    .build());
            log.info("文件已上传对象存储 bucket={} key={} size={}", bucket, objectKey, contentLength);
            return "s3://" + bucket + "/" + objectKey;
        } catch (Exception e) {
            throw new StorageException("对象存储上传失败 bucket=" + bucket + " key=" + objectKey + "：" + e.getMessage(), e);
        }
    }

    @Override
    public InputStream download(String bucket, String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new StorageException("对象存储读取失败 bucket=" + bucket + " key=" + objectKey + "：" + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            log.info("对象存储文件已删除 bucket={} key={}", bucket, objectKey);
        } catch (Exception e) {
            throw new StorageException("对象存储删除失败 bucket=" + bucket + " key=" + objectKey + "：" + e.getMessage(), e);
        }
    }

    @Override
    public URL presignedUrl(String bucket, String objectKey, int expireMinutes) {
        try {
            String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expireMinutes, TimeUnit.MINUTES)
                    .build());
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new StorageException("预签名 URL 生成失败 bucket=" + bucket + " key=" + objectKey, e);
        } catch (Exception e) {
            throw new StorageException("对象存储预签名失败 bucket=" + bucket + " key=" + objectKey + "：" + e.getMessage(), e);
        }
    }
}
