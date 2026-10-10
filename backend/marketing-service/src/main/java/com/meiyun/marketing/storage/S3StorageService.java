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
 * MinIO/S3 直连存储（棒⑧卡4；F3 卡2 ㉕ 内外双轨修透）。普通类非 Spring bean：
 * 由 DelegatingStorageService 门面按目录快照启用时懒装配（config hash 变化即重建），
 * 未启用时 classpath 中 MinioClient 完全不初始化，local 兜底零影响。
 *
 * <p><b>内外双轨（㉕）：</b>endpoint 为内轨（容器网络内可达，承载全部 I/O 读写删）；
 * publicEndpoint 为外轨（浏览器/外部可达，仅用于预签名 URL 生成）。SigV4 预签名把 host
 * 签进签名串，事后字符串重写 host 会破坏签名，故外轨用独立 MinioClient——
 * getPresignedObjectUrl 为纯本地计算不发请求，双 client 零网络成本。
 * publicEndpoint 缺省=内轨 endpoint（与棒⑧卡4 单 endpoint 原口径兼容）。
 */
public class S3StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    /** 内轨 client：I/O 读写删唯一通道（endpoint 内网可达）。 */
    private final MinioClient client;
    /** 外轨 client：仅预签名 URL 生成（publicEndpoint 外部可达）；缺省与内轨同实例。 */
    private final MinioClient presignClient;

    public S3StorageService(String endpoint, String publicEndpoint, String region, String accessKey, String secretKey) {
        this.client = build(endpoint, region, accessKey, secretKey);
        this.presignClient = (publicEndpoint == null || publicEndpoint.isBlank() || publicEndpoint.equals(endpoint))
                ? this.client
                : build(publicEndpoint, region, accessKey, secretKey);
    }

    private static MinioClient build(String endpoint, String region, String accessKey, String secretKey) {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey);
        if (region != null && !region.isBlank()) {
            builder.region(region);
        }
        return builder.build();
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
            // 外轨生成：URL host=publicEndpoint（外部可达）；SigV4 签名随 host 绑定，重写即失效
            String url = presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
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
