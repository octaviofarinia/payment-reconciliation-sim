package org.octavio.paymentreconciliationsim.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.octavio.paymentreconciliationsim.worker.*;
import software.amazon.awssdk.auth.credentials.*;
import static org.junit.jupiter.api.Assertions.*;
class LambdaConfigurationTest {
 @Test void usesExplicitRoleProviderAndBoundsBothCallAndAttemptWithoutResolvingHostCredentials() {
  var config=new LambdaConfiguration();
  var environment=new MockEnvironment().withProperty("reconciliation.aws.region","us-east-1")
    .withProperty("reconciliation.lambda.function-name","worker");
  var credentials=StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy","dummy"));
  try(var client=config.lambdaClient(environment,credentials)) {
   var override=client.serviceClientConfiguration().overrideConfiguration();
   assertEquals(java.time.Duration.ofSeconds(20),override.apiCallTimeout().orElseThrow());
   assertEquals(java.time.Duration.ofSeconds(10),override.apiCallAttemptTimeout().orElseThrow());
   assertInstanceOf(AwsLambdaInvoker.class,config.lambdaInvoker(client,environment));
   assertThrows(IllegalStateException.class,()->config.lambdaInvoker(client,new MockEnvironment()));
  }
 }
}
