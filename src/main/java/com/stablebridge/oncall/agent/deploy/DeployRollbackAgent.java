package com.stablebridge.oncall.agent.deploy;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.hitl.WaitFor;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.persona.OnCallPersonas;
import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.deploy.RollbackHistory;
import com.stablebridge.oncall.domain.model.deploy.RollbackResult;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.argocd.DeployRollbackProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.RollbackReportFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Agent(
        description =
                "Execute deployment rollback with human-in-the-loop approval gate."
                        + " Gathers rollback context, assesses risk via LLM,"
                        + " requests human confirmation, then executes rollback via ArgoCD")
@Slf4j
@RequiredArgsConstructor
public class DeployRollbackAgent {

    private final DeployHistoryProvider deployHistoryProvider;
    private final DeployRollbackProvider deployRollbackProvider;
    private final MetricsProvider metricsProvider;
    private final RollbackReportFormatter rollbackReportFormatter;

    // Blackboard state records
    public record RollbackQuery(String service) {}

    public record RollbackContextData(
            RollbackHistory history, DeploySnapshot currentDeploy) {}

    public record RollbackPlan(
            String service,
            String targetRevision,
            String currentRevision,
            String riskSummary,
            String expectedImpact) {}

    public record ApprovedRollbackPlan(RollbackPlan plan) {}

    public record RollbackExecutionResult(RollbackResult result) {}

    public record FormattedRollbackReport(
            String service, RollbackResult result, String markdown) {}

    @Action(description = "Parse rollback request to extract service name")
    public RollbackQuery parseRollbackRequest(UserInput userInput) {
        var content = userInput.getContent().trim();
        log.info("Parsing rollback request from input: {}", content);
        String service = content.split("\\s+")[0];
        return new RollbackQuery(service);
    }

    @Action(
            description =
                    "Fetch rollback context from ArgoCD including current deploy and rollback history")
    public RollbackContextData fetchRollbackContext(RollbackQuery query) {
        log.info("Fetching rollback context for service: {}", query.service());
        DeploySnapshot currentDeploy =
                deployHistoryProvider.fetchLatestDeploy(query.service());
        RollbackHistory history =
                deployHistoryProvider.fetchRollbackHistory(query.service());
        return new RollbackContextData(history, currentDeploy);
    }

    @Action(
            description =
                    "Assess rollback risk using Senior SRE persona to evaluate impact and generate a plan")
    public RollbackPlan assessRollbackPlan(
            RollbackQuery query, RollbackContextData context, Ai ai) {
        log.info("Assessing rollback plan for service: {}", query.service());

        var history = context.history();
        var deploy = context.currentDeploy();

        String prompt =
                String.format(
                        """
                        Assess the rollback risk for service '%s' and produce a RollbackPlan.

                        ## Current Deploy
                        - Revision: %s
                        - Deployed At: %s
                        - Health: %s
                        - Sync Status: %s

                        ## Rollback Target
                        - Can Rollback: %s
                        - Target Revision: %s
                        - Available Revisions: %s

                        Provide your assessment as a RollbackPlan with:
                        1. service: the service name
                        2. targetRevision: the revision to roll back to
                        3. currentRevision: the current revision
                        4. riskSummary: brief risk assessment (1-2 sentences)
                        5. expectedImpact: expected impact of the rollback (1-2 sentences)
                        """,
                        query.service(),
                        deploy.lastDeployId(),
                        deploy.deployedAt(),
                        deploy.health(),
                        deploy.syncStatus(),
                        history.canRollback(),
                        history.lastStableRevision(),
                        String.join(", ", history.previousRevisions()));

        return ai.withDefaultLlm()
                .withPromptContributor(OnCallPersonas.SENIOR_SRE)
                .creating(RollbackPlan.class)
                .fromPrompt(prompt);
    }

    @Action(description = "Request human approval before executing rollback")
    public ApprovedRollbackPlan requestHumanApproval(RollbackPlan plan) {
        log.info(
                "Requesting human approval for rollback of {} from {} to {}",
                plan.service(),
                plan.currentRevision(),
                plan.targetRevision());

        String message =
                String.format(
                        """
                        ROLLBACK APPROVAL REQUIRED

                        Service: %s
                        Current Revision: %s
                        Target Revision: %s

                        Risk: %s
                        Expected Impact: %s

                        Do you approve this rollback?""",
                        plan.service(),
                        plan.currentRevision(),
                        plan.targetRevision(),
                        plan.riskSummary(),
                        plan.expectedImpact());

        RollbackPlan confirmed = WaitFor.confirmation(plan, message);
        log.info("Rollback approved by operator for service: {}", confirmed.service());
        return new ApprovedRollbackPlan(confirmed);
    }

    @Action(description = "Execute rollback via ArgoCD after human approval")
    public RollbackExecutionResult executeRollback(ApprovedRollbackPlan approved) {
        var plan = approved.plan();
        log.info(
                "Executing rollback for service: {} to revision: {}",
                plan.service(),
                plan.targetRevision());

        RollbackResult result =
                deployRollbackProvider.executeRollback(
                        plan.service(), plan.targetRevision());
        return new RollbackExecutionResult(result);
    }

    @AchievesGoal(
            description = "Produce formatted rollback report after execution",
            export =
                    @Export(
                            name = "executeRollback",
                            remote = true,
                            startingInputTypes = {UserInput.class}))
    @Action(description = "Format rollback result as markdown report")
    public FormattedRollbackReport formatReport(
            RollbackExecutionResult execution, RollbackQuery query) {
        log.info(
                "Formatting rollback report for service: {} — success: {}",
                query.service(),
                execution.result().success());
        String markdown = rollbackReportFormatter.format(execution.result());
        return new FormattedRollbackReport(
                query.service(), execution.result(), markdown);
    }
}
