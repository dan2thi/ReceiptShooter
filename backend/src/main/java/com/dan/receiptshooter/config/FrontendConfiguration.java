package com.dan.receiptshooter.config;

import java.io.IOException;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
class FrontendConfiguration implements WebMvcConfigurer {
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webManifestMimeType() {
        return factory -> {
            var mappings = new MimeMappings();
            mappings.add("webmanifest", "application/manifest+json");
            factory.addMimeMappings(mappings);
        };
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource found = super.getResource(path, location);
                        if (found != null) return found;
                        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
                        if (path.equals("api") || path.startsWith("api/") || path.equals("assets") ||
                                path.startsWith("assets/") || lastSegment.contains(".")) return null;
                        return super.getResource("index.html", location);
                    }
                });
    }
}
