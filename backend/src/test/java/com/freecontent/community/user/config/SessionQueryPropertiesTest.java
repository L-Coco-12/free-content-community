package com.freecontent.community.user.config;

import com.freecontent.community.config.PropertyBindingTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class SessionQueryPropertiesTest {
    private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner=PropertyBindingTestSupport.runner(UserModuleConfiguration.class);
    @Test void approvedDefaultsBind(){runner.run(context->{assertThat(context).hasNotFailed();var p=context.getBean(SessionQueryProperties.class);assertThat(p.ipPerMinuteLimit()).isEqualTo(120);assertThat(p.userPerMinuteLimit()).isEqualTo(60);});}
    @ParameterizedTest @ValueSource(strings={"ip-per-minute-limit=0","user-per-minute-limit=0","redis-key-prefix=","redis-key-prefix=bad{slot}"})
    void invalidSettingsStopStartup(String value){runner.withPropertyValues("app.user.session-query."+value).run(context->assertThat(context).hasFailed());}
}
