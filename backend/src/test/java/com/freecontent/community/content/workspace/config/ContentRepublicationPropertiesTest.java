package com.freecontent.community.content.workspace.config;

import com.freecontent.community.config.PropertyBindingTestSupport;
import com.freecontent.community.content.config.ContentModuleConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentRepublicationPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(ContentModuleConfiguration.class)
            .withPropertyValues("app.content.publication-republish.ip-per-minute-limit=120", "app.content.publication-republish.user-per-minute-limit=60",
                    "app.content.publication-republish.redis-key-prefix=test:republish:");

    @Test
    void validSettingsBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ContentRepublicationProperties.class);
            assertThat(context.getBean(ContentRepublicationProperties.class)).isEqualTo(new ContentRepublicationProperties(120, 60, "test:republish:"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "user-per-minute-limit=0", "ip-per-minute-limit=-1", "user-per-minute-limit=-1",
            "redis-key-prefix=", "redis-key-prefix=bad{slot}", "redis-key-prefix=bad}"})
    void invalidSettingsStopStartup(String value) {
        runner.withPropertyValues("app.content.publication-republish." + value).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nullPrefixIsRejected() { assertThrows(IllegalArgumentException.class, () -> new ContentRepublicationProperties(120, 60, null)); }
}
