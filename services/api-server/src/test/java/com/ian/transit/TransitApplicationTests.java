package com.ian.transit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        // Disable production pipeline schema validation for context smoke tests.
        "spring.jpa.hibernate.ddl-auto=none",

        // Force Hibernate schema tooling off even if another profile sets validation.
        "spring.jpa.properties.hibernate.hbm2ddl.auto=none",

        // Do not run schema/data SQL initialization scripts in this smoke test.
        "spring.sql.init.mode=never"
})
class TransitApplicationTests {

    @Test
    void contextLoads() {
    }
}
