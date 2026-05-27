package gg.modl.backend.storage.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.storage.config.S3Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Error;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    @Test
    void deleteFileReturnsFalseWhenS3ReportsDeleteErrors() {
        S3Client s3Client = mock(S3Client.class);
        S3StorageService service = new S3StorageService(s3Client, null, configuration());

        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
            ListObjectVersionsResponse.builder()
                .versions(ObjectVersion.builder().key("db/replays/replay.modlreplay").versionId("v1").build())
                .isTruncated(false)
                .build()
        );
        when(s3Client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(
            DeleteObjectsResponse.builder()
                .errors(S3Error.builder()
                    .key("db/replays/replay.modlreplay")
                    .code("InternalError")
                    .message("transient failure")
                    .build())
                .build()
        );

        assertFalse(service.deleteFile("db/replays/replay.modlreplay"));
    }

    @Test
    void deleteFileTreatsAlreadyMissingObjectAsDeleted() {
        S3Client s3Client = mock(S3Client.class);
        S3StorageService service = new S3StorageService(s3Client, null, configuration());

        when(s3Client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
            ListObjectVersionsResponse.builder()
                .isTruncated(false)
                .build()
        );

        assertTrue(service.deleteFile("db/replays/replay.modlreplay"));
    }

    private S3Configuration configuration() {
        S3Configuration configuration = new S3Configuration();
        configuration.setBucketName("bucket");
        return configuration;
    }
}
