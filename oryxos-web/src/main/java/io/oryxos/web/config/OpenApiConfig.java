package io.oryxos.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T008 — springdoc-openapi 元信息 bean.
 *
 * <p>声明 title / version / contact / license;{@code /v3/api-docs} + {@code /swagger-ui.html} 自动生成.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI oryxosWebOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("OryxOS Web Service API")
                .version("0.1.0")
                .description("""
                    OryxOS core-stage capability 5 — Web Service.
                    10 REST endpoints exposed at /api/v1/* for business systems to integrate
                    Agent invocation into production workflows.

                    Aligned with:
                    - CLAUDE.md §15 (10 endpoints)
                    - 008-agent-scheduler (session.metadata.source="web" / task_executions.trigger_source="web")
                    - 005-tool-system / 006-memory-layer / 007-sandbox-whitelist (audit day-one contracts)
                    """)
                .contact(new Contact()
                    .name("OryxOS Team")
                    .url("https://oryxos.io"))
                .license(new License()
                    .name("Apache-2.0")
                    .url("https://www.apache.org/licenses/LICENSE-2.0.html")));
    }
}