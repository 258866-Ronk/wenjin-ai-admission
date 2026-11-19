package cn.wenjin.admission.web;

import cn.wenjin.admission.ai.AiConfigurationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai-config")
public class AiConfigController {
    private final AiConfigurationService configuration;

    public AiConfigController(AiConfigurationService configuration) {
        this.configuration = configuration;
    }

    @GetMapping
    Object get() {
        return configuration.view();
    }

    @PutMapping
    Object update(Authentication authentication, @RequestBody AiConfigurationService.UpdateRequest request) {
        return configuration.update(request, WebSupport.user(authentication).id());
    }

    @PostMapping("/test")
    Object test() {
        return configuration.testCurrent();
    }
}
