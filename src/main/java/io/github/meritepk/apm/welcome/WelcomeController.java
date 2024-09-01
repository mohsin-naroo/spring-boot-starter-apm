package io.github.meritepk.apm.welcome;

import java.time.LocalDateTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/welcome")
public class WelcomeController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @GetMapping
    public ResponseEntity<Map<String, Object>> welcome() {
        Map<String, Object> data = Map.of("message", "welcome", "createdAt", LocalDateTime.now());
        logger.info("welcome: {}", data);
        return ResponseEntity.ok(data);
    }
}
