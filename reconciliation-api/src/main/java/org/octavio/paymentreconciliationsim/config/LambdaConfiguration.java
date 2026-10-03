package org.octavio.paymentreconciliationsim.config;

import java.time.Duration;
import org.octavio.paymentreconciliationsim.worker.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;

@Configuration(proxyBeanMethods=false)
@ConditionalOnMissingBean(LambdaInvoker.class)
public class LambdaConfiguration {
 @Bean(destroyMethod="close")
 public LambdaClient lambdaClient(Environment environment,AwsCredentialsProvider credentials) {
  return LambdaClient.builder().region(Region.of(environment.getRequiredProperty("reconciliation.aws.region")))
    .credentialsProvider(credentials).overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(20))
      .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
 }
 @Bean public LambdaInvoker lambdaInvoker(LambdaClient client,Environment environment) {
  return new AwsLambdaInvoker(client,environment.getRequiredProperty("reconciliation.lambda.function-name"));
 }
}
