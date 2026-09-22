package org.example.grab.global.common;

import java.util.List;

// 공통 페이지 응답
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}
