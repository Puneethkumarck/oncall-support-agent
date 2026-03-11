package com.stablebridge.oncall.application.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Slf4j
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class PagerDutyWebhookController {

    @PostMapping("/pagerduty")
    public ResponseEntity<Void> handlePagerDutyWebhook(@RequestBody JsonNode payload) {
        log.info(
                "Received PagerDuty webhook: {}",
                payload.has("event")
                        ? payload.get("event").get("event_type").asText()
                        : "unknown");
        // TODO: Invoke IncidentTriageAgent in M7
        return ResponseEntity.accepted().build();
    }
}
