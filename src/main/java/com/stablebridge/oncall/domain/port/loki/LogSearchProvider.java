package com.stablebridge.oncall.domain.port.loki;

import com.stablebridge.oncall.domain.model.logs.LogCluster;

import java.time.Instant;
import java.util.List;

public interface LogSearchProvider {
    List<LogCluster> searchLogs(String service, Instant from, Instant to, String severityFilter);
}
