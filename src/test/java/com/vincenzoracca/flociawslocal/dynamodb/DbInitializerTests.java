package com.vincenzoracca.flociawslocal.dynamodb;

import com.vincenzoracca.flociawslocal.FlociConfiguration;
import io.awspring.cloud.dynamodb.DynamoDbTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "floci-aws-local.enable-db-initializer=true",
        "floci-aws-local.enable-outbox-v0=false",
        "floci-aws-local.enable-outbox-v1=false",
        "floci-aws-local.enable-outbox-v2=false"
})
@Import(FlociConfiguration.class)
class DbInitializerTests {

    @Autowired
    private DynamoDbTemplate dynamoDbTemplate;

    @Test
    void testDynamoDb() {
        PageIterable<Person> page = dynamoDbTemplate.scan(ScanEnhancedRequest.builder().build(), Person.class);
        List<Person> items = page.items().stream().toList();
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).getName()).isEqualTo("John Doe");
        assertThat(items.get(0).getLastName()).isEqualTo("Smith");
    }
}
