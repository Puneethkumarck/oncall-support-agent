package com.stablebridge.oncall.domain.model.fatigue;

import java.util.List;

public record DuplicateGroup(List<String> alertIds, String suggestedGrouping) {}
