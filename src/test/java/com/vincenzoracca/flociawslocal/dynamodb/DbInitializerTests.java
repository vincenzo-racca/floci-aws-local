package com.vincenzoracca.flociawslocal.dynamodb;

import com.vincenzoracca.flociawslocal.FlociConfiguration;
import com.vincenzoracca.flociawslocal.sqs.consumer.PersonStreamConsumer;
import io.awspring.cloud.dynamodb.DynamoDbTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(FlociConfiguration.class)
class DbInitializerTests {

    @Autowired
    private DynamoDbTemplate dynamoDbTemplate;

    @MockitoSpyBean
    private PersonStreamConsumer personStreamConsumer;

    @Test
    void testDynamoDb() {
        PageIterable<Person> page = dynamoDbTemplate.scan(ScanEnhancedRequest.builder().build(), Person.class);
        List<Person> items = page.items().stream().toList();
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).getName()).isEqualTo("John Doe");
        assertThat(items.get(0).getLastName()).isEqualTo("Smith");
    }

    @Test
    void testPipeForwardsInsertToSqsConsumer() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(personStreamConsumer, timeout(30_000)).listen(payload.capture());

        assertThat(payload.getValue()).contains("\"eventName\"", "\"INSERT\"", "\"NewImage\"");
    }
}
