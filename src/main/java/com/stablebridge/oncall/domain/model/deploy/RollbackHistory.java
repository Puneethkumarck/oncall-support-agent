package com.stablebridge.oncall.domain.model.deploy;

import java.util.List;

public record RollbackHistory(
        List<String> previousRevisions,
        boolean canRollback,
        String currentRevision,
        String lastStableRevision) {}
