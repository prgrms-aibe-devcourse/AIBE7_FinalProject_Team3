package org.example.grab.domain.drop.entity.option;

import org.example.grab.domain.drop.entity.Drop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class DropOptionTest {

    @Test
    @DisplayName("가용 재고는 total - reserved - sold - withheld 로 계산한다")
    void calculatesAvailableQuantity() {
        // given
        DropOption option = DropOption.create(Drop.createDraft(1L), 10000L, 10, 0);
        ReflectionTestUtils.setField(option, "reservedQuantity", 2);
        ReflectionTestUtils.setField(option, "soldQuantity", 3);
        ReflectionTestUtils.setField(option, "withheldQuantity", 1);

        // when
        int availableQuantity = option.getAvailableQuantity();

        // then
        assertThat(availableQuantity).isEqualTo(4);
    }
}
