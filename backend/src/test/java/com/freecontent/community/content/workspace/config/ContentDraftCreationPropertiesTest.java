package com.freecontent.community.content.workspace.config;

import com.freecontent.community.config.PropertyBindingTestSupport;
import com.freecontent.community.content.config.ContentModuleConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentDraftCreationPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(ContentModuleConfiguration.class)
            .withPropertyValues("app.content.draft-creation.ip-per-minute-limit=60", "app.content.draft-creation.user-hourly-limit=100",
                    "app.content.draft-creation.redis-key-prefix=test:draft:");

    @Test
    void validSettingsBindInTheContentModule() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ContentDraftCreationProperties.class);
            assertThat(context.getBean(ContentDraftCreationProperties.class)).isEqualTo(new ContentDraftCreationProperties(60, 100, "test:draft:"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "user-hourly-limit=0", "ip-per-minute-limit=-1", "user-hourly-limit=-1",
            "redis-key-prefix=", "redis-key-prefix=bad{slot}", "redis-key-prefix=bad}"})
    void invalidSettingsStopStartup(String value) {
        runner.withPropertyValues("app.content.draft-creation." + value).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nullPrefixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ContentDraftCreationProperties(60, 100, null));
    }
}
