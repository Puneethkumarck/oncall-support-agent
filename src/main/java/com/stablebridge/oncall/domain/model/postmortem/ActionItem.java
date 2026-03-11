package com.stablebridge.oncall.domain.model.postmortem;

public record ActionItem(
        String description, String owner, String priority, String dueDate) {}
