package org.example.grab;

import org.example.grab.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GrabApplicationTests {

    @Test
    void contextLoads() {
    }

}
