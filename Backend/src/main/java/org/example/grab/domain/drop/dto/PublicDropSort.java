package org.example.grab.domain.drop.dto;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.data.domain.Sort;

/*
 * 공개 DROP 목록의 정렬 조건.
 * Spring Data는 네이티브 쿼리에 Sort를 붙일 때 쿼리에서 감지한 별칭(d)을 속성 앞에 다시 붙인다.
 * 그래서 여기에는 별칭 없는 컬럼명(published_at 등)만 담고 별칭은 Spring Data가 붙이게 둔다.
 * (d.published_at처럼 넘기면 d.d.published_at이 되어 SQL 오류가 난다.)
 * 허용 속성(publishedAt·saleStartsAt·createdAt)과 방향(asc·desc)이 아니면 INVALID_REQUEST로 거부한다.
 * 값이 같으면 id 내림차순을 타이브레이커로 붙인다.
 */
public record PublicDropSort(String column, Sort.Direction direction) {

    public static PublicDropSort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new PublicDropSort("published_at", Sort.Direction.DESC);
        }
        String[] parts = raw.split(",", -1);
        if (parts.length > 2) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        String column = switch (parts[0].trim()) {
            case "publishedAt" -> "published_at";
            case "saleStartsAt" -> "sale_starts_at";
            case "createdAt" -> "created_at";
            default -> throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        };
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length == 2) {
            direction = switch (parts[1].trim()) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
            };
        }
        return new PublicDropSort(column, direction);
    }

    public Sort toSort() {
        return Sort.by(new Sort.Order(direction, column)).and(Sort.by("id").descending());
    }
}
