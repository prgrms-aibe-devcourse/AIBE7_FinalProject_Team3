package org.example.grab.domain.drop.dto;

import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.springframework.data.domain.Sort;

/*
 * 공개 DROP 목록의 정렬 조건.
 * 날짜 정렬(publishedAt·saleStartsAt·createdAt)은 Spring Data Sort로 넘긴다.
 * Spring Data는 네이티브 쿼리에 Sort를 붙일 때 쿼리에서 감지한 별칭(d)을 속성 앞에 다시 붙인다.
 * 그래서 column에는 별칭 없는 컬럼명(published_at 등)만 담고 별칭은 Spring Data가 붙이게 둔다.
 * (d.published_at처럼 넘기면 d.d.published_at이 되어 SQL 오류가 난다.) 값이 같으면 id 내림차순을 붙인다.
 * 인기 정렬(wishCount·soldQuantity)은 집계값이라 Sort로 표현할 수 없어 column 없이 type만 담고,
 * 정렬은 전용 쿼리가 고정한다. 내림차순만 허용하며 지정된 status와 함께일 때만 쓸 수 있다(DROP.md 1.2).
 * 허용되지 않은 속성·방향·상태 조합은 INVALID_REQUEST로 거부한다.
 */
public record PublicDropSort(Type type, String column, Sort.Direction direction) {

    public enum Type {
        DATE(null),
        WISH_COUNT(DropStatus.WISH),
        SOLD_QUANTITY(DropStatus.GRAB);

        // 인기 정렬이 허용되는 유일한 status. 날짜 정렬은 제한이 없어 null이다.
        private final DropStatus requiredStatus;

        Type(DropStatus requiredStatus) {
            this.requiredStatus = requiredStatus;
        }
    }

    public static PublicDropSort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new PublicDropSort(Type.DATE, "published_at", Sort.Direction.DESC);
        }
        String[] parts = raw.split(",", -1);
        if (parts.length > 2) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length == 2) {
            direction = switch (parts[1].trim()) {
                case "asc" -> Sort.Direction.ASC;
                case "desc" -> Sort.Direction.DESC;
                default -> throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
            };
        }
        return switch (parts[0].trim()) {
            case "publishedAt" -> new PublicDropSort(Type.DATE, "published_at", direction);
            case "saleStartsAt" -> new PublicDropSort(Type.DATE, "sale_starts_at", direction);
            case "createdAt" -> new PublicDropSort(Type.DATE, "created_at", direction);
            case "wishCount" -> popularity(Type.WISH_COUNT, direction);
            case "soldQuantity" -> popularity(Type.SOLD_QUANTITY, direction);
            default -> throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        };
    }

    private static PublicDropSort popularity(Type type, Sort.Direction direction) {
        if (direction != Sort.Direction.DESC) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return new PublicDropSort(type, null, direction);
    }

    // 인기 정렬은 status 생략·다른 상태와 함께 쓰면 거부한다. 날짜 정렬은 어떤 status와도 쓸 수 있다.
    public void requireCompatibleWith(DropStatus status) {
        if (type.requiredStatus != null && type.requiredStatus != status) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    public boolean isPopularity() {
        return type != Type.DATE;
    }

    public Sort toSort() {
        if (isPopularity()) {
            throw new IllegalStateException("인기 정렬은 Sort로 표현하지 않는다: " + type);
        }
        return Sort.by(new Sort.Order(direction, column)).and(Sort.by("id").descending());
    }
}
