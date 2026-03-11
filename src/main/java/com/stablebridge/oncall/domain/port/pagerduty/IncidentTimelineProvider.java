package com.stablebridge.oncall.domain.port.pagerduty;

import com.stablebridge.oncall.domain.model.postmortem.TimelineEntry;

import java.util.List;

public interface IncidentTimelineProvider {
    List<TimelineEntry> fetchIncidentTimeline(String incidentId);
}
