package org.example.grab.global.storage.supabase;

/**
 * 서명 업로드 URL 발급 결과.
 *
 * @param uploadUrl 클라이언트가 파일을 직접 업로드할 Supabase 서명 URL(만료 2시간)
 * @param imageUrl  업로드 후 조회에 쓰는 공개 버킷 URL
 */
public record SignedUploadUrl(String uploadUrl, String imageUrl) {
}
