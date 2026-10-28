package org.example.grab.domain.demand.embedding;

import org.example.grab.domain.demand.embedding.DropEmbeddingText.OptionGroup;
import org.example.grab.domain.demand.embedding.DropEmbeddingText.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DropEmbeddingTextTest {

    @Test
    @DisplayName("카테고리·상품명·설명·옵션을 정해진 형식으로 잇고, 줄바꿈·연속 공백은 공백 하나로 바꾼다")
    void build_formatsCatalogText() {
        Source source = new Source("패션", " 오버핏\n티셔츠 ", "부드러운   코튼\n\n티셔츠",
                List.of(new OptionGroup("색상", List.of("블랙", "화이트")), new OptionGroup("사이즈", List.of("S", "M"))));

        assertThat(DropEmbeddingText.build(source)).isEqualTo("""
                카테고리: 패션
                상품명: 오버핏 티셔츠
                설명: 부드러운 코튼 티셔츠
                옵션: 색상(블랙, 화이트) / 사이즈(S, M)""");
    }

    @Test
    @DisplayName("옵션이 없으면 옵션 줄을 빼고, 설명은 코드 포인트 기준 500자까지만 쓴다")
    void build_omitsOptionsAndTruncatesDescription() {
        // 이모지는 서로게이트 쌍이라 char 단위로 자르면 깨진다
        String description = "😀".repeat(DropEmbeddingText.DESCRIPTION_LIMIT + 10);

        String text = DropEmbeddingText.build(new Source("리빙", "머그컵", description, List.of()));

        assertThat(text).doesNotContain("옵션:");
        String written = text.substring(text.indexOf("설명: ") + "설명: ".length());
        assertThat(written.codePointCount(0, written.length())).isEqualTo(DropEmbeddingText.DESCRIPTION_LIMIT);
        assertThat(written).isEqualTo("😀".repeat(DropEmbeddingText.DESCRIPTION_LIMIT));
    }

    @Test
    @DisplayName("입력 해시는 모델과 입력이 같으면 같고, 하나라도 다르면 달라진다")
    void sourceHash_dependsOnModelAndText() {
        String hash = DropEmbeddingText.sourceHash("gemini-embedding-001", "text");

        assertThat(hash).hasSize(64).isEqualTo(DropEmbeddingText.sourceHash("gemini-embedding-001", "text"));
        assertThat(DropEmbeddingText.sourceHash("other-model", "text")).isNotEqualTo(hash);
        assertThat(DropEmbeddingText.sourceHash("gemini-embedding-001", "text2")).isNotEqualTo(hash);
    }
}
