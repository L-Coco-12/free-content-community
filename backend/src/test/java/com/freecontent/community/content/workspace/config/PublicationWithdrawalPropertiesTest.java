package com.freecontent.community.content.workspace.config;

import com.freecontent.community.config.PropertyBindingTestSupport;
import com.freecontent.community.content.config.ContentModuleConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicationWithdrawalPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(ContentModuleConfiguration.class)
            .withPropertyValues("app.content.publication-withdrawal.ip-per-minute-limit=120", "app.content.publication-withdrawal.user-per-minute-limit=60",
                    "app.content.publication-withdrawal.redis-key-prefix=test:withdraw:");

    @Test
    void validSettingsBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PublicationWithdrawalProperties.class);
            assertThat(context.getBean(PublicationWithdrawalProperties.class)).isEqualTo(new PublicationWithdrawalProperties(120, 60, "test:withdraw:"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "user-per-minute-limit=0", "ip-per-minute-limit=-1", "user-per-minute-limit=-1",
            "redis-key-prefix=", "redis-key-prefix=bad{slot}", "redis-key-prefix=bad}"})
    void invalidSettingsStopStartup(String value) {
        runner.withPropertyValues("app.content.publication-withdrawal." + value).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nullPrefixIsRejected() { assertThrows(IllegalArgumentException.class, () -> new PublicationWithdrawalProperties(120, 60, null)); }
}
