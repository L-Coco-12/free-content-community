package com.freecontent.community.content.moderation.config;

import com.freecontent.community.config.PropertyBindingTestSupport;
import com.freecontent.community.content.config.ContentModuleConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentTakeDownPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(ContentModuleConfiguration.class)
            .withPropertyValues("app.content.take-down.ip-per-minute-limit=60", "app.content.take-down.user-hourly-limit=100",
                    "app.content.take-down.redis-key-prefix=test:take-down:");

    @Test
    void validSettingsBindInTheContentModule() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ContentTakeDownProperties.class);
            assertThat(context.getBean(ContentTakeDownProperties.class)).isEqualTo(new ContentTakeDownProperties(60, 100, "test:take-down:"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "user-hourly-limit=0", "ip-per-minute-limit=-1", "user-hourly-limit=-1",
            "redis-key-prefix=", "redis-key-prefix=bad{slot}", "redis-key-prefix=bad}"})
    void invalidSettingsStopStartup(String value) {
        runner.withPropertyValues("app.content.take-down." + value).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nullPrefixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ContentTakeDownProperties(60, 100, null));
    }
}
