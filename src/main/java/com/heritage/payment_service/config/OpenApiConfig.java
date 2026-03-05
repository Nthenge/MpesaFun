package com.heritage.payment_service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI heritageReportOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Heritage Payment Service")
                        .description("API for Payment Service")
                        .version("1.0"));
    }
}
