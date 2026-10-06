package org.example.grab.global.storage.supabase;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Supabase Storage의 서명 업로드 URL 응답(ospec: POST /object/upload/sign/{bucket}/{path}). token은 로그·응답에 남기지 않는다.
@JsonIgnoreProperties(ignoreUnknown = true)
record SupabaseSignedUploadResponse(String url, String token) {
}
