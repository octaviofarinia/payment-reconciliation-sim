package org.octavio.paymentreconciliationsim.config;
import org.junit.jupiter.api.Test;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import static org.junit.jupiter.api.Assertions.*;
class OpenApiConfigurationTest {
 @Test void schemasDocumentExactInputUnitsBoundsAndBearerAccess(){
  var api=new OpenApiConfiguration().publicApi();assertEquals("v1",api.getInfo().getVersion());assertEquals("Payment reconciliation simulator",api.getInfo().getTitle());
  assertEquals(SecurityScheme.Type.HTTP,api.getComponents().getSecuritySchemes().get("demoBearer").getType());assertEquals("bearer",api.getComponents().getSecuritySchemes().get("demoBearer").getScheme());assertTrue(api.getSecurity().getFirst().containsKey("demoBearer"));
  var schemas=api.getComponents().getSchemas();assertTrue(schemas.containsKey("ApiError"));assertEquals(java.util.List.of("businessDate"),schemas.get("CreateBusinessDate").getRequired());
  var purchase=schemas.get("CreatePurchase");assertEquals(Boolean.FALSE,purchase.getAdditionalProperties());assertEquals(5,purchase.getRequired().size());java.util.Map<String,io.swagger.v3.oas.models.media.Schema> props=purchase.getProperties();
  assertEquals("^[A-Za-z0-9_-]{1,64}$",props.get("transactionReference").getPattern());assertEquals(1,props.get("merchantId").getMinLength());assertEquals(64,props.get("merchantId").getMaxLength());
  assertEquals("date",props.get("businessDate").getFormat());assertEquals("int64",props.get("amountCentavos").getFormat());assertEquals(java.math.BigDecimal.ONE,props.get("amountCentavos").getMinimum());assertEquals(java.math.BigDecimal.valueOf(Long.MAX_VALUE),props.get("amountCentavos").getMaximum());assertTrue(props.get("amountCentavos").getDescription().contains("centavos"));assertEquals(java.util.List.of("ARS"),props.get("currency").getEnum());
  var registration=schemas.get("RegisterRun");java.util.Map<String,io.swagger.v3.oas.models.media.Schema> registrationProperties=registration.getProperties();assertEquals(java.util.Set.of("businessDate","sha256","byteLength"),new java.util.HashSet<>(registration.getRequired()));assertEquals("^[a-f0-9]{64}$",registrationProperties.get("sha256").getPattern());assertEquals(java.math.BigDecimal.ONE,registrationProperties.get("byteLength").getMinimum());assertEquals(java.math.BigDecimal.valueOf(2097152),registrationProperties.get("byteLength").getMaximum());assertEquals("int64",registrationProperties.get("byteLength").getFormat());assertEquals("date",registrationProperties.get("businessDate").getFormat());
 }
 @Test void everyPublicOperationReceivesBearerSecurityAndTheStableErrorSchema(){
  var config=new OpenApiConfiguration();var get=new Operation().responses(new ApiResponses());var post=new Operation().responses(new ApiResponses());var api=config.publicApi().paths(new Paths().addPathItem("/api/v1/example",new PathItem().get(get).post(post)));
  api.getComponents().getSchemas().remove("ApiError");
  config.publicContracts().customise(api);
  assertTrue(api.getComponents().getSchemas().containsKey("ApiError"));
  for(var op:java.util.List.of(get,post)){assertTrue(op.getSecurity().getFirst().containsKey("demoBearer"));for(int status:new int[]{400,401,403,404,405,406,409,413,415,500,503}){var response=op.getResponses().get(Integer.toString(status));assertNotNull(response);assertEquals("#/components/schemas/ApiError",response.getContent().get("application/json").getSchema().get$ref());assertNotNull(response.getContent().get("application/json").getExample());}}
 }
}
