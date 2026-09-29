package org.example.grab.global;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalPackageDependencyTest {

    private static final Path GLOBAL_SOURCE_ROOT = Path.of("src/main/java/org/example/grab/global");

    @Test
    @DisplayName("global 패키지는 특정 도메인의 코드를 참조하지 않는다")
    // 도메인 오류 코드 연결은 각 도메인이 ConstraintErrorCodeMapping 빈으로 등록한다(CODING_CONVENTION.md 2.1, GR-28 M06-06)
    void doesNotReferenceDomainPackages() throws IOException {
        // given
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(GLOBAL_SOURCE_ROOT)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }

        // when
        List<Path> referencingDomain = sources.stream()
                .filter(path -> read(path).contains("org.example.grab.domain"))
                .toList();

        // then
        assertThat(sources).isNotEmpty();
        assertThat(referencingDomain).isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
