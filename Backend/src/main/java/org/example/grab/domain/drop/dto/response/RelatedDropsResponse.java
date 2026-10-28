package org.example.grab.domain.drop.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// 관련 DROP 추천(DROP.md 1.4). basis로 화면이 문구를 고르며, 추천이 없으면 basis는 null·drops는 빈 배열이다
public record RelatedDropsResponse(
        @Schema(nullable = true) RelatedBasis basis,
        List<PublicDropListResponse> drops
) {

    // CO_WISH: 함께 WISH한 DROP, SIMILAR: 같은 카테고리에서 내용이 비슷한 DROP(임베딩)
    public enum RelatedBasis {
        CO_WISH,
        SIMILAR
    }

    public static RelatedDropsResponse empty() {
        return new RelatedDropsResponse(null, List.of());
    }
}
