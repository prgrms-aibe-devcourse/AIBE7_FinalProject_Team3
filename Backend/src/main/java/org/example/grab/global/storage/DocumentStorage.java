package org.example.grab.global.storage;

// 비공개 버킷에 암호문 바이트를 직접 저장한다. ImageStorage와 달리 공개 URL을 발급하지 않는다.
public interface DocumentStorage {

    void store(String objectKey, byte[] data);

    byte[] load(String objectKey);
}
