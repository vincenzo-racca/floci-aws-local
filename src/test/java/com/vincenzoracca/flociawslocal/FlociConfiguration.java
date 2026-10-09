package com.vincenzoracca.flociawslocal;

import io.floci.testcontainers.FlociContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.containers.BindMode;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;

@TestConfiguration
public class FlociConfiguration {

    static final FlociContainer flociContainer =
            new FlociContainer(DockerImageName.parse("floci/floci:2.2.0-compat"))
                    .withFileSystemBind(Path.of("config", "init-aws.sh").toAbsolutePath().toString(),
                            "/etc/localstack/init/ready.d/init-aws.sh", BindMode.READ_ONLY)
                    .withEnv("AWS_ACCESS_KEY_ID", "test")
                    .withEnv("AWS_SECRET_ACCESS_KEY", "test")
                    .withEnv("AWS_DEFAULT_REGION", "us-east-1");

    static {
        flociContainer.start();
        System.setProperty("spring.cloud.aws.endpoint", flociContainer.getEndpoint());
    }
}
