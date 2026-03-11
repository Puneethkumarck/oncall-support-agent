package com.stablebridge.oncall.domain.port.argocd;

import com.stablebridge.oncall.domain.model.deploy.DeployDetail;
import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.deploy.RollbackHistory;

import java.time.Instant;
import java.util.List;

public interface DeployHistoryProvider {
    DeploySnapshot fetchLatestDeploy(String appName);

    DeployDetail fetchDeployDetail(String appName, String revision);

    RollbackHistory fetchRollbackHistory(String appName);

    List<DeploySnapshot> fetchDeploysInWindow(String appName, Instant from, Instant to);
}
