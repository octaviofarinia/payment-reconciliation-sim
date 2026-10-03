package org.octavio.paymentreconciliationsim.config;

import java.math.BigDecimal;
import java.util.List;
import org.octavio.paymentreconciliationsim.http.ApiError;
import org.octavio.paymentreconciliationsim.http.ApiExceptionHandler;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.*;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    public OpenAPI publicApi() {
        var components = new Components().addSecuritySchemes("demoBearer", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").description("Configured demo bearer token"));
        ModelConverters.getInstance().read(ApiError.class).forEach(components::addSchemas);
        components.addSchemas("CreateBusinessDate", new ObjectSchema().addProperty("businessDate", new DateSchema())
                .required(List.of("businessDate")));
        components.addSchemas("CreatePurchase", new ObjectSchema().additionalProperties(false)
                .addProperty("transactionReference", new StringSchema().pattern("^[A-Za-z0-9_-]{1,64}$"))
                .addProperty("merchantId", new StringSchema().minLength(1).maxLength(64).description("1 to 64 Unicode codepoints; preserved without normalization"))
                .addProperty("businessDate", new DateSchema())
                .addProperty("amountCentavos", new IntegerSchema().format("int64").minimum(BigDecimal.ONE)
                    .maximum(BigDecimal.valueOf(Long.MAX_VALUE)).description("Positive integer ARS centavos; fractional numbers rejected"))
                .addProperty("currency", new StringSchema()._enum(List.of("ARS")))
                .required(List.of("transactionReference", "merchantId", "businessDate", "amountCentavos", "currency")));
        components.addSchemas("RegisterRun", new ObjectSchema().addProperty("businessDate", new DateSchema())
                .addProperty("sha256", new StringSchema().pattern("^[a-f0-9]{64}$"))
                .addProperty("byteLength", new IntegerSchema().format("int64").minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(2_097_152)))
                .required(List.of("businessDate", "sha256", "byteLength")));
        return new OpenAPI().info(new Info().title("Payment reconciliation simulator").version("v1"))
                .components(components).addSecurityItem(new SecurityRequirement().addList("demoBearer"));
    }

    @Bean
    public OpenApiCustomizer publicContracts() {
        return api -> {
            // springdoc removes initially unreferenced schemas before operation customization.
            ModelConverters.getInstance().read(ApiError.class).forEach(api.getComponents()::addSchemas);
            api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                operation.setSecurity(List.of(new SecurityRequirement().addList("demoBearer")));
                for (int status : new int[]{400, 401, 403, 404, 405, 406, 409, 413, 415, 500, 503}) {
                    operation.getResponses().addApiResponse(Integer.toString(status), new ApiResponse()
                            .description("Sanitized error with a server-generated correlationId")
                            .content(new Content().addMediaType("application/json", new MediaType()
                                    .schema(new Schema<>().$ref("#/components/schemas/ApiError"))
                                    .example(ApiExceptionHandler.error(status,
                                            "00000000-0000-0000-0000-000000000001")))));
                }
            }));
        };
    }
}
