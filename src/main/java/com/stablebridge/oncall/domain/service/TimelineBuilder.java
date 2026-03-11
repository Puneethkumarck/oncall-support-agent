package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.postmortem.TimelineEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TimelineBuilder {

    public List<TimelineEntry> build(List<List<TimelineEntry>> sources) {
        List<TimelineEntry> merged = new ArrayList<>();
        for (List<TimelineEntry> source : sources) {
            if (source != null) {
                merged.addAll(source);
            }
        }
        merged.sort(Comparator.comparing(TimelineEntry::timestamp));
        return List.copyOf(merged);
    }
}
