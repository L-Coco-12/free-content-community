package com.freecontent.community.media.config;

import com.freecontent.community.config.PropertyBindingTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class MediaProcessingPropertiesTest {
    private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner=
            PropertyBindingTestSupport.runner(MediaModuleConfiguration.class);
    @Test void configuredDefaultsUseTheApprovedImageBudgets() {
        runner.run(context->{
            assertThat(context).hasNotFailed();
            var p=context.getBean(MediaProcessingProperties.class);
            assertThat(p.maxWidth()).isEqualTo(8192); assertThat(p.maxHeight()).isEqualTo(8192);
            assertThat(p.maxFramePixels()).isEqualTo(25_000_000); assertThat(p.maxFrames()).isEqualTo(100);
            assertThat(p.maxTotalPixels()).isEqualTo(100_000_000);
        });
    }
    @ParameterizedTest @ValueSource(strings={"poll-delay-millis=0","lease-seconds=60","max-attempts=0",
            "retry-base-seconds=0","orphan-grace-seconds=1","max-width=0","max-height=0","max-frame-pixels=0",
            "max-frames=0","max-total-pixels=0","decode-timeout-seconds=0","decode-timeout-seconds=2147483647"})
    void invalidResourceAndLeaseSettingsFailAtStartup(String property) {
        runner.withPropertyValues("app.media.processing."+property).run(context->assertThat(context).hasFailed());
    }
}
