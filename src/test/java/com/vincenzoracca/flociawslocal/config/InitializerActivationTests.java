package com.vincenzoracca.flociawslocal.config;

import com.vincenzoracca.flociawslocal.dynamodb.DbInitializer;
import com.vincenzoracca.flociawslocal.outbox.v0.OutboxInitializerV0;
import com.vincenzoracca.flociawslocal.outbox.v1.OutboxInitializerV1;
import com.vincenzoracca.flociawslocal.outbox.v2.OutboxInitializerV2;
import io.awspring.cloud.dynamodb.DynamoDbTemplate;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import tools.jackson.databind.ObjectMapper;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InitializerActivationTests {

    private static final Class<?>[] INITIALIZERS = {
            DbInitializer.class, OutboxInitializerV0.class,
            OutboxInitializerV1.class, OutboxInitializerV2.class
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(INITIALIZERS)
            .withBean(DynamoDbTemplate.class, () -> mock(DynamoDbTemplate.class))
            .withBean(DynamoDbEnhancedClient.class, () -> mock(DynamoDbEnhancedClient.class))
            .withBean(SqsTemplate.class, () -> mock(SqsTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void missingActivationPropertiesLeaveAllInitializersDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> initializer : INITIALIZERS) {
                assertThat(context).doesNotHaveBean(initializer);
            }
        });
    }

    @ParameterizedTest
    @MethodSource("initializers")
    void truePropertyEnablesOnlyItsInitializer(String property, Class<?> enabledInitializer) {
        contextRunner.withPropertyValues("floci-aws-local." + property + "=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(enabledInitializer);
                    for (Class<?> initializer : INITIALIZERS) {
                        if (initializer != enabledInitializer) {
                            assertThat(context).doesNotHaveBean(initializer);
                        }
                    }
                });
    }

    @ParameterizedTest
    @MethodSource("initializers")
    void falsePropertyDisablesItsInitializer(String property, Class<?> initializer) {
        contextRunner.withPropertyValues("floci-aws-local." + property + "=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(initializer));
    }

    private static Stream<Arguments> initializers() {
        return Stream.of(
                Arguments.of("enable-db-initializer", DbInitializer.class),
                Arguments.of("enable-outbox-v0", OutboxInitializerV0.class),
                Arguments.of("enable-outbox-v1", OutboxInitializerV1.class),
                Arguments.of("enable-outbox-v2", OutboxInitializerV2.class));
    }
}
