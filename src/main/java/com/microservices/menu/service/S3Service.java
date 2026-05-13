package com.microservices.menu.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Service
public class S3Service {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.region}")
    private String region;

    public S3Service(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public String uploadImage(MultipartFile file) throws IOException {
        String key = "menu-items/" + UUID.randomUUID() + "-" + file.getOriginalFilename();

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType(file.getContentType())
                        .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize())
        );

        String url = "https://" + bucketName + ".s3." + region + ".amazonaws.com/" + key;
        log.info("Uploaded image to S3: {}", url);
        return url;
    }

    public void deleteImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return;

        String key = extractKey(imageUrl);
        if (key == null) {
            log.warn("Could not extract S3 key from URL: {}", imageUrl);
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            log.info("Deleted S3 object: {}", key);
        } catch (Exception e) {
            log.warn("Failed to delete S3 object {}: {}", key, e.getMessage());
        }
    }

    private String extractKey(String imageUrl) {
        // Virtual-hosted style: https://{bucket}.s3.{region}.amazonaws.com/{key}
        String virtualHosted = "https://" + bucketName + ".s3." + region + ".amazonaws.com/";
        if (imageUrl.startsWith(virtualHosted)) {
            return imageUrl.substring(virtualHosted.length());
        }
        // Path style: https://s3.{region}.amazonaws.com/{bucket}/{key}
        String pathStyle = "https://s3." + region + ".amazonaws.com/" + bucketName + "/";
        if (imageUrl.startsWith(pathStyle)) {
            return imageUrl.substring(pathStyle.length());
        }
        return null;
    }
}
