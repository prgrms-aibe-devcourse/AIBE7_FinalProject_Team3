package org.example.grab.domain.drop.dto;

import org.example.grab.domain.drop.entity.DropStatus;
import org.example.grab.global.error.BusinessException;
import org.example.grab.global.error.CommonErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicDropSortTest {

    @Test
    @DisplayName("sort가 없으면 publishedAt 내림차순에 id 내림차순 타이브레이커를 붙인다")
    void defaultsToPublishedAtDesc() {
        // when
        PublicDropSort sort = PublicDropSort.parse(null);

        // then
        assertThat(sort.type()).isEqualTo(PublicDropSort.Type.DATE);
        assertThat(sort.isPopularity()).isFalse();
        assertThat(sort.toSort()).containsExactly(
                Sort.Order.desc("published_at"), Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("날짜 정렬은 컬럼명과 방향으로 변환한다")
    void parsesDateSort() {
        // when
        PublicDropSort sort = PublicDropSort.parse("saleStartsAt,asc");

        // then
        assertThat(sort.toSort()).containsExactly(
                Sort.Order.asc("sale_starts_at"), Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("wishCount·soldQuantity는 내림차순 인기 정렬로 해석한다")
    void parsesPopularitySort() {
        // when
        PublicDropSort wish = PublicDropSort.parse("wishCount,desc");
        PublicDropSort grab = PublicDropSort.parse("soldQuantity,desc");

        // then
        assertThat(wish.type()).isEqualTo(PublicDropSort.Type.WISH_COUNT);
        assertThat(wish.isPopularity()).isTrue();
        assertThat(grab.type()).isEqualTo(PublicDropSort.Type.SOLD_QUANTITY);
        assertThat(grab.isPopularity()).isTrue();
    }

    @Test
    @DisplayName("인기 정렬에 방향을 생략하면 날짜 정렬과 같이 내림차순이다")
    void popularitySortDefaultsToDesc() {
        // when
        PublicDropSort sort = PublicDropSort.parse("wishCount");

        // then
        assertThat(sort.type()).isEqualTo(PublicDropSort.Type.WISH_COUNT);
        assertThat(sort.direction()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("인기 정렬의 오름차순은 INVALID_REQUEST로 거부한다")
    void rejectsAscendingPopularitySort() {
        assertInvalid(() -> PublicDropSort.parse("wishCount,asc"));
        assertInvalid(() -> PublicDropSort.parse("soldQuantity,asc"));
    }

    @Test
    @DisplayName("허용되지 않은 속성·방향·형식은 INVALID_REQUEST로 거부한다")
    void rejectsMalformedSort() {
        assertInvalid(() -> PublicDropSort.parse("name,asc"));
        assertInvalid(() -> PublicDropSort.parse("createdAt,sideways"));
        assertInvalid(() -> PublicDropSort.parse("wishCount,desc,id"));
    }

    @Test
    @DisplayName("인기 정렬은 지정된 status와 함께일 때만 허용한다")
    void allowsPopularitySortOnlyWithRequiredStatus() {
        // given
        PublicDropSort wish = PublicDropSort.parse("wishCount,desc");
        PublicDropSort grab = PublicDropSort.parse("soldQuantity,desc");

        // when & then
        assertThatCode(() -> wish.requireCompatibleWith(DropStatus.WISH)).doesNotThrowAnyException();
        assertThatCode(() -> grab.requireCompatibleWith(DropStatus.GRAB)).doesNotThrowAnyException();
        assertInvalid(() -> wish.requireCompatibleWith(null));
        assertInvalid(() -> wish.requireCompatibleWith(DropStatus.GRAB));
        assertInvalid(() -> wish.requireCompatibleWith(DropStatus.ENDED));
        assertInvalid(() -> grab.requireCompatibleWith(null));
        assertInvalid(() -> grab.requireCompatibleWith(DropStatus.WISH));
        assertInvalid(() -> grab.requireCompatibleWith(DropStatus.ENDED));
    }

    @Test
    @DisplayName("날짜 정렬은 status 생략·모든 공개 상태와 함께 쓸 수 있다")
    void allowsDateSortWithAnyStatus() {
        // given
        PublicDropSort sort = PublicDropSort.parse("createdAt,asc");

        // when & then
        assertThatCode(() -> sort.requireCompatibleWith(null)).doesNotThrowAnyException();
        assertThatCode(() -> sort.requireCompatibleWith(DropStatus.WISH)).doesNotThrowAnyException();
        assertThatCode(() -> sort.requireCompatibleWith(DropStatus.GRAB)).doesNotThrowAnyException();
        assertThatCode(() -> sort.requireCompatibleWith(DropStatus.ENDED)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("인기 정렬은 Spring Data Sort로 변환하지 않는다")
    void popularitySortHasNoSpringSort() {
        // given
        PublicDropSort sort = PublicDropSort.parse("soldQuantity,desc");

        // when & then
        assertThatThrownBy(sort::toSort).isInstanceOf(IllegalStateException.class);
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_REQUEST);
    }
}
