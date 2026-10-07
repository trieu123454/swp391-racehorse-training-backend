package com.example.springbootbackend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

import org.springdoc.core.properties.SwaggerUiConfigParameters;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

@Configuration
public class SwaggerTokenValidationConfig {
    @Bean
    SwaggerIndexTransformer swaggerIndexTransformer(SwaggerUiConfigProperties config,
            SwaggerUiOAuthProperties oauth, SwaggerUiConfigParameters parameters,
            SwaggerWelcomeCommon welcome, ObjectMapperProvider mapper) throws IOException {
        return new ValidatingIndexTransformer(config, oauth, parameters, welcome, mapper);
    }

    private static final class ValidatingIndexTransformer extends SwaggerIndexPageTransformer {
        private final String plugin;

        ValidatingIndexTransformer(SwaggerUiConfigProperties config, SwaggerUiOAuthProperties oauth,
                SwaggerUiConfigParameters parameters, SwaggerWelcomeCommon welcome,
                ObjectMapperProvider mapper) throws IOException {
            super(config, oauth, parameters, welcome, mapper);
            try (var input = new ClassPathResource("swagger-token-validation.js").getInputStream()) {
                plugin = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        @Override
        public Resource transform(HttpServletRequest request, Resource resource,
                ResourceTransformerChain chain) throws IOException {
            Resource transformed = super.transform(request, resource, chain);
            if (!"swagger-initializer.js".equals(transformed.getFilename())) return transformed;

            String script;
            try (var input = transformed.getInputStream()) {
                script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            String marker = "plugins: [";
            if (!script.contains(marker)) throw new IOException("Swagger UI plugin insertion point was not found");
            String validated = plugin + "\n" + script.replace(marker,
                    marker + "\n      SwaggerTokenValidationPlugin,");
            return new TransformedResource(transformed, validated.getBytes(StandardCharsets.UTF_8));
        }
    }
}
