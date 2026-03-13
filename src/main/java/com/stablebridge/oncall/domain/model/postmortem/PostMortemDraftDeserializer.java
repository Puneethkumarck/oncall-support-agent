package com.stablebridge.oncall.domain.model.postmortem;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stablebridge.oncall.domain.model.common.IncidentSeverity;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PostMortemDraftDeserializer extends JsonDeserializer<PostMortemDraft> {

    @Override
    public PostMortemDraft deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        JsonNode root = p.getCodec().readTree(p);

        // Unwrap if LLM wrapped in "postMortemDraft" or "draft"
        if (root.has("postMortemDraft")) {
            root = root.get("postMortemDraft");
        } else if (root.has("draft")) {
            root = root.get("draft");
        }

        String title = textOrNull(root, "title");
        IncidentSeverity severity = parseSeverity(root);
        ImpactSummary impact = parseImpact(root.get("impact"));
        List<TimelineEntry> timeline = parseTimeline(root.get("timeline"));
        String rootCause = textOrNull(root, "rootCause");
        List<String> contributingFactors = parseStringList(root.get("contributingFactors"));
        List<String> whatWentWell = parseStringList(root.get("whatWentWell"));
        List<String> whatWentPoorly = parseStringList(root.get("whatWentPoorly"));
        List<ActionItem> actionItems = parseActionItems(root.get("actionItems"));
        List<String> lessonsLearned = parseStringList(root.get("lessonsLearned"));

        return new PostMortemDraft(
                title, severity, impact, timeline, rootCause,
                contributingFactors, whatWentWell, whatWentPoorly,
                actionItems, lessonsLearned);
    }

    private IncidentSeverity parseSeverity(JsonNode root) {
        String text = textOrNull(root, "severity");
        if (text == null) return null;
        try {
            return IncidentSeverity.valueOf(text.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ImpactSummary parseImpact(JsonNode node) {
        if (node == null || node.isNull()) return null;

        // Parse duration — handle strings like "~5 minutes", "PT5M", "5 minutes"
        Duration duration = null;
        String durationText = textOrNull(node, "duration");
        if (durationText != null) {
            try {
                duration = Duration.parse(durationText);
            } catch (Exception e) {
                // Try to extract minutes from freeform text
                var matcher = java.util.regex.Pattern.compile("(\\d+)\\s*min").matcher(durationText);
                if (matcher.find()) {
                    duration = Duration.ofMinutes(Long.parseLong(matcher.group(1)));
                }
            }
        }

        // Parse affectedUsers — handle int, string, or alternative field names
        int affectedUsers = 0;
        if (node.has("affectedUsers")) {
            JsonNode au = node.get("affectedUsers");
            if (au.isNumber()) {
                affectedUsers = au.asInt();
            } else if (au.isTextual()) {
                try { affectedUsers = Integer.parseInt(au.asText().replaceAll("[^0-9]", "")); }
                catch (NumberFormatException ignored) {}
            }
        } else if (node.has("affectedUsersEstimate")) {
            JsonNode au = node.get("affectedUsersEstimate");
            if (au.isNumber()) {
                affectedUsers = au.asInt();
            }
        }

        List<String> affectedServices = parseStringList(node.get("affectedServices"));

        return new ImpactSummary(duration, affectedUsers, affectedServices);
    }

    private List<TimelineEntry> parseTimeline(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<TimelineEntry> entries = new ArrayList<>();
        for (JsonNode entry : node) {
            Instant timestamp = null;
            String tsText = textOrNull(entry, "timestamp");
            if (tsText != null) {
                try { timestamp = Instant.parse(tsText); }
                catch (Exception ignored) {}
            }
            String event = textOrNull(entry, "event");
            String actor = textOrNull(entry, "actor");
            entries.add(new TimelineEntry(timestamp, event, actor));
        }
        return entries;
    }

    private List<ActionItem> parseActionItems(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<ActionItem> items = new ArrayList<>();
        for (JsonNode item : node) {
            items.add(new ActionItem(
                    textOrNull(item, "description"),
                    textOrNull(item, "owner"),
                    textOrNull(item, "priority"),
                    textOrNull(item, "dueDate")));
        }
        return items;
    }

    private List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            list.add(item.asText());
        }
        return list;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }
}
