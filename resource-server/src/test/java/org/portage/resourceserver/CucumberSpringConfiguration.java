package org.portage.resourceserver;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
/** Configure les tests Cucumber avec le JWKS local simulé par JwksFixture. */
public class CucumberSpringConfiguration {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("resource-server.jwks-uri", JwksFixture::uri);
    }
}
