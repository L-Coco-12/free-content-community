package com.freecontent.community.user.config;

import com.freecontent.community.config.PropertyBindingTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class AvatarAccessPropertiesTest {
    private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner=
            PropertyBindingTestSupport.runner(UserModuleConfiguration.class);
    @Test void documentedDefaultsBindWithAnOptionalPublicOrigin() {
        runner.run(context->{
            assertThat(context).hasNotFailed();var p=context.getBean(AvatarAccessProperties.class);
            assertThat(p.readUrlTtlSeconds()).isEqualTo(300);assertThat(p.ipPerMinuteLimit()).isEqualTo(120);
            assertThat(p.userPerMinuteLimit()).isEqualTo(60);assertThat(p.publicBaseUrl()).isEmpty();
        });
    }
    @ParameterizedTest @ValueSource(strings={"read-url-ttl-seconds=0","read-url-ttl-seconds=604801","ip-per-minute-limit=0",
            "user-per-minute-limit=0","redis-key-prefix=","redis-key-prefix=bad{slot}","public-base-url=ftp://example.test",
            "public-base-url=https://user:password@example.test","public-base-url=https://example.test?x=1",
            "public-base-url=https://example.test#fragment","public-base-url=http://example.test:0","public-base-url=http://example.test:65536"})
    void unsafeConfigurationFailsAtStartup(String value) {
        runner.withPropertyValues("app.user.avatar-access."+value).run(context->assertThat(context).hasFailed());
    }
}
