package io.github.temporalrift.workbench.browser;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import io.github.temporalrift.workbench.ExecutionTestConfiguration;
import io.github.temporalrift.workbench.TestcontainersConfiguration;

/**
 * The packaged application on a real port with its real token validation, so a browser can sign in at a real
 * identity provider. Runs execute on scripted in-process games, so they are real but deterministic.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ExecutionTestConfiguration.class})
public @interface BrowserIntegrationTest {}
