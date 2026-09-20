package com.meiyun.finance.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@ConditionalOnProperty(name = "storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path rootDir;

    public LocalStorageService(@Value("${storage.local.root:/data/meiyun-storage}") String root) {
        this.rootDir = Path.of(root);
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            log.warn("存储根目录创建失败 root={} : {}", root, e.getMessage());
        }
    }

    @Override
    public String upload(String bucket, String objectKey, InputStream data, long contentLength, String contentType) {
        Path target = resolve(bucket, objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("文件已存储 bucket={} key={} size={}", bucket, objectKey, contentLength);
            return target.toString();
        } catch (IOException e) {
            throw new StorageException("文件上传失败 bucket=" + bucket + " key=" + objectKey, e);
        }
    }

    @Override
    public InputStream download(String bucket, String objectKey) {
        Path target = resolve(bucket, objectKey);
        if (!Files.exists(target)) {
            throw new StorageException("文件不存在 bucket=" + bucket + " key=" + objectKey);
        }
        try {
            return new FileInputStream(target.toFile());
        } catch (IOException e) {
            throw new StorageException("文件读取失败 bucket=" + bucket + " key=" + objectKey, e);
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        Path target = resolve(bucket, objectKey);
        try {
            Files.deleteIfExists(target);
            log.info("文件已删除 bucket={} key={}", bucket, objectKey);
        } catch (IOException e) {
            throw new StorageException("文件删除失败 bucket=" + bucket + " key=" + objectKey, e);
        }
    }

    @Override
    public URL presignedUrl(String bucket, String objectKey, int expireMinutes) {
        Path target = resolve(bucket, objectKey);
        if (!Files.exists(target)) {
            throw new StorageException("文件不存在 bucket=" + bucket + " key=" + objectKey);
        }
        try {
            return target.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new StorageException("预签名 URL 生成失败 bucket=" + bucket + " key=" + objectKey, e);
        }
    }

    private Path resolve(String bucket, String objectKey) {
        if (bucket == null || bucket.isBlank()) {
            throw new StorageException("bucket 不能为空");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new StorageException("objectKey 不能为空");
        }
        if (objectKey.contains("..")) {
            throw new StorageException("objectKey 不允许包含路径穿越字符");
        }
        return rootDir.resolve(bucket).resolve(objectKey).normalize();
    }

    public static class StorageException extends RuntimeException {
        public StorageException(String message) { super(message); }
        public StorageException(String message, Throwable cause) { super(message, cause); }
    }
}
