package com.stablebridge.oncall.domain.port.tempo;

import com.stablebridge.oncall.domain.model.trace.CallChainStep;

import java.time.Instant;
import java.util.List;

public interface TraceProvider {
    List<CallChainStep> fetchTrace(String traceId);

    List<CallChainStep> searchTraces(String service, Instant from, Instant to, int limit);
}
