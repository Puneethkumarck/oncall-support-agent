package com.stablebridge.oncall.domain.port.argocd;

import com.stablebridge.oncall.domain.model.deploy.RollbackResult;

public interface DeployRollbackProvider {
    RollbackResult executeRollback(String appName, String targetRevision);
}
