package com.stablebridge.oncall.domain.port.prometheus;

import com.stablebridge.oncall.domain.model.health.DependencyStatus;

import java.util.List;

public interface DependencyGraphProvider {
    List<DependencyStatus> fetchDependencies(String service);
}
