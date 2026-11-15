package com.freecontent.community.content.interaction.config;

import com.freecontent.community.config.PropertyBindingTestSupport;
import com.freecontent.community.content.config.ContentModuleConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class ContentBookmarkPropertiesTest {
    private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner=PropertyBindingTestSupport.runner(ContentModuleConfiguration.class);
    @Test void approvedDefaultsBind(){runner.run(context->{assertThat(context).hasNotFailed();var p=context.getBean(ContentBookmarkProperties.class);assertThat(p.ipPerMinuteLimit()).isEqualTo(120);assertThat(p.userPerMinuteLimit()).isEqualTo(60);});}
    @ParameterizedTest @ValueSource(strings={"ip-per-minute-limit=0","user-per-minute-limit=0","redis-key-prefix=","redis-key-prefix=bad{slot}"})
    void invalidConfigurationFailsAtStartup(String value){runner.withPropertyValues("app.content.bookmark."+value).run(context->assertThat(context).hasFailed());}
}
