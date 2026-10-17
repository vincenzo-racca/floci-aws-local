package com.vincenzoracca.flociawslocal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "floci-aws-local")
@Data
public class AppProperties {

    private String envValue;
    private boolean enableDbInitializer;
    private boolean enableOutboxV0;
    private boolean enableOutboxV1;
}
