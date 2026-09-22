package org.example.grab.global.common;

// 공통 api 응답
public record ApiResponse<T>(
        boolean success,
        T data,
        String message
) {

    /*
        1.4 명세가 성공 응답의 message를 null 로 정의하므로..
        필요한 API가 생길 때만 별도 오버로드 추가
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }
}
