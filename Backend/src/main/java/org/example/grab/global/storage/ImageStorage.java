package org.example.grab.global.storage;

public interface ImageStorage {

    SignedUploadUrl createSignedUploadUrl(String objectKey);

    String publicUrl(String objectKey);
}
