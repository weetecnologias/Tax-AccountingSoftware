package com.taxpilot.documents.storage;

import java.net.URI;

import com.taxpilot.config.TaxpilotProperties;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** S3-compatible storage (AWS S3, MinIO, etc.). Objects are already AES-GCM encrypted by the application. */
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final String bucket;
    private final boolean requestSse;

    public S3ObjectStorage(TaxpilotProperties.Storage.S3 cfg) {
        var builder = S3Client.builder()
                .region(Region.of(cfg.region() == null ? "ap-south-1" : cfg.region()))
                .forcePathStyle(true);
        if (cfg.endpoint() != null && !cfg.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.endpoint()));
        }
        if (cfg.accessKey() != null && !cfg.accessKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())));
        }
        this.s3 = builder.build();
        this.bucket = cfg.bucket();
        // MinIO without KMS rejects SSE headers; request SSE only against AWS.
        this.requestSse = cfg.endpoint() == null || cfg.endpoint().isBlank();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @Override
    public void put(String key, byte[] data) {
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType("application/octet-stream");
        if (requestSse) {
            req.serverSideEncryption(ServerSideEncryption.AES256);
        }
        s3.putObject(req.build(), RequestBody.fromBytes(data));
    }

    @Override
    public byte[] get(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public String kind() {
        return "s3";
    }
}
