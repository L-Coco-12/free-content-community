package com.freecontent.community.content.comment.config;

import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CommentOpenApiConfiguration {
    @Bean
    public OpenApiCustomizer optionalPublicCommentAuthentication() {
        return api -> {
            if (api.getPaths() == null) return;
            for (String route : List.of("/api/v1/comments/{commentId}", "/api/v1/contents/{contentId}/comments",
                    "/api/v1/comments/{commentId}/replies")) {
                var path = api.getPaths().get(route);
                if (path != null && path.getGet() != null) {
                    path.getGet().setSecurity(List.of(new SecurityRequirement(), new SecurityRequirement().addList("bearerAuth")));
                }
            }
        };
    }

    @Bean
    public OpenApiCustomizer nullableCommentAuthor() {
        return api -> {
            var components = api.getComponents();
            if (components == null || components.getSchemas() == null) return;
            var comment = components.getSchemas().get("Comment");
            if (comment == null) return;

            var author = new ComposedSchema();
            author.addAnyOfItem(new Schema<>().$ref("#/components/schemas/PublicUser"));
            var nullAuthor = new Schema<>();
            nullAuthor.setTypes(Set.of("null"));
            author.addAnyOfItem(nullAuthor);
            comment.addProperty("author", author);
        };
    }

    @Bean
    public OpenApiCustomizer commentPageCursorContract() {
        return api -> {
            var components = api.getComponents();
            if (components == null || components.getSchemas() == null) return;
            var page = components.getSchemas().get("CommentPage");
            if (page == null) return;

            var condition = new ObjectSchema();
            condition.addProperty("hasMore", new BooleanSchema()._const(true));
            condition.addRequiredItem("hasMore");
            var nextCursor = new Schema<>();
            nextCursor.setTypes(Set.of("string"));
            nextCursor.setMinLength(1);
            var nextPage = new ObjectSchema();
            nextPage.addProperty("nextCursor", nextCursor);
            var nullCursor = new Schema<>();
            nullCursor.setTypes(Set.of("null"));
            var lastPage = new ObjectSchema();
            lastPage.addProperty("nextCursor", nullCursor);
            var rule = new Schema<>();
            rule.setIf(condition);
            rule.setThen(nextPage);
            rule.setElse(lastPage);
            page.setAllOf(List.of(rule));
        };
    }
}
