package cn.wenjin.admission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import cn.wenjin.admission.config.AppProperties;

@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
@SpringBootApplication
public class WenjinApplication {
    public static void main(String[] args) {
        SpringApplication.run(WenjinApplication.class, args);
    }
}
