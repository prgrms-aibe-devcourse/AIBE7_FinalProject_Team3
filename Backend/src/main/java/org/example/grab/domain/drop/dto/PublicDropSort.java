package org.example.grab.domain.drop.dto;

import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.data.domain.Sort;

/*
 * 공개 DROP 목록의 정렬 조건.
 * 사용자 입력 문자열을 허용 목록에 있는 컬럼명으로만 매핑해 SQL에 넣는다.
 * 허용 속성(publishedAt·saleStartsAt·createdAt)과 방향(asc·desc)이 아니면 INVALID_REQUEST로 거부한다.
 * 값이 같으면 id 내림차순을 타이브레이커로 붙인다.
 */
public record PublicDropSort(String column, Sort.Direction direction) {

    public static PublicDropSort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new PublicDropSort("d.published_at", Sort.Direction.DESC);
        }
        String[] parts = raw.split(",", -1);
        if (parts.length > 2) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        String column = switch (parts[0].trim()) {
            case "publishedAt" -> "d.published_at";
            case "saleStartsAt" -> "d.sale_starts_at";
            case "createdAt" -> "d.created_at";
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
        return Sort.by(new Sort.Order(direction, column)).and(Sort.by("d.id").descending());
    }
}
