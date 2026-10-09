package com.vincenzoracca.flociawslocal.parameterstore;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "floci-aws-local")
public class FlociAwsLocalProperties {

    private String envValue;

    public String getEnvValue() {
        return envValue;
    }

    public void setEnvValue(String envValue) {
        this.envValue = envValue;
    }
}
