package com.stablebridge.oncall.domain.model.deploy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Duration;

public class DeployCorrelationDeserializer extends JsonDeserializer<DeployCorrelation> {

    @Override
    public DeployCorrelation deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        // Handle boolean primitive: "correlatedChange": false
        if (p.currentToken() == JsonToken.VALUE_FALSE) {
            return new DeployCorrelation(false, null, null);
        }
        if (p.currentToken() == JsonToken.VALUE_TRUE) {
            return new DeployCorrelation(true, null, null);
        }
        // Handle null
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        // Handle full object: "correlatedChange": { "isCorrelated": true, ... }
        if (p.currentToken() == JsonToken.START_OBJECT) {
            boolean isCorrelated = false;
            String deployId = null;
            Duration timeDelta = null;

            while (p.nextToken() != JsonToken.END_OBJECT) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "isCorrelated" -> isCorrelated = p.getBooleanValue();
                    case "deployId" -> deployId = p.getText();
                    case "timeDelta" -> {
                        String text = p.getText();
                        if (text != null && !text.isBlank()) {
                            try {
                                timeDelta = Duration.parse(text);
                            } catch (Exception e) {
                                timeDelta = null;
                            }
                        }
                    }
                    default -> p.skipChildren();
                }
            }
            return new DeployCorrelation(isCorrelated, deployId, timeDelta);
        }
        // Handle string "false"/"true"
        if (p.currentToken() == JsonToken.VALUE_STRING) {
            return new DeployCorrelation(Boolean.parseBoolean(p.getText()), null, null);
        }
        return new DeployCorrelation(false, null, null);
    }
}
