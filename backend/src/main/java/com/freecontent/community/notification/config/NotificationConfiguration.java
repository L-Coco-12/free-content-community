package com.freecontent.community.notification.config;

import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        NotificationQueryProperties.class, NotificationCursorProperties.class, NotificationReadMarkProperties.class
})
public class NotificationConfiguration {
    @Bean
    public OpenApiCustomizer notificationPageContract() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            var page = api.getComponents().getSchemas().get("NotificationPage");
            if (page == null) return;
            var condition = new ObjectSchema().addProperty("hasMore", new BooleanSchema()._const(true))
                    .addRequiredItem("hasMore");
            var nullCursor = new Schema<>();
            nullCursor.setTypes(Set.of("null"));
            var rule = new Schema<>();
            rule.setIf(condition);
            rule.setThen(new ObjectSchema().addProperty("nextCursor", new StringSchema().minLength(1)));
            rule.setElse(new ObjectSchema().addProperty("nextCursor", nullCursor));
            page.setAllOf(List.of(rule));
        };
    }
}
