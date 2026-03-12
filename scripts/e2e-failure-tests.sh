#!/usr/bin/env bash
# =============================================================================
# E2E Failure Injection Test Script
# Tests oncall-support-agent against price-alert system with injected failures
#
# Prerequisites:
#   1. price-alert stack running: cd ../price-alert && ./scripts/launch.sh up
#   2. Mock services running: ./scripts/e2e-stack.sh up
#   3. oncall-support-agent running with e2e profile:
#      ./gradlew bootRun --args='--spring.profiles.active=e2e'
#
# Usage:
#   ./scripts/e2e-failure-tests.sh              # Run all scenarios
#   ./scripts/e2e-failure-tests.sh evaluator     # Run single scenario
#   ./scripts/e2e-failure-tests.sh --list        # List available scenarios
# =============================================================================
set -euo pipefail

PRICE_ALERT_DIR="${PRICE_ALERT_DIR:-/Users/puneethkumarck/Documents/AI/github/price-alert}"
AGENT_BASE_URL="${AGENT_BASE_URL:-http://localhost:8090}"
COMPOSE_CMD="docker compose -f ${PRICE_ALERT_DIR}/docker-compose.yml"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

PASSED=0
FAILED=0
SKIPPED=0

# --- Helper Functions ---

log_info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
log_pass()  { echo -e "${GREEN}[PASS]${NC}  $*"; ((PASSED++)); }
log_fail()  { echo -e "${RED}[FAIL]${NC}  $*"; ((FAILED++)); }
log_skip()  { echo -e "${YELLOW}[SKIP]${NC}  $*"; ((SKIPPED++)); }
log_step()  { echo -e "${YELLOW}  -->  ${NC}$*"; }

wait_for_service() {
    local service=$1
    local port=$2
    local max_wait=${3:-30}
    local elapsed=0
    while ! curl -sf "http://localhost:${port}/actuator/health" > /dev/null 2>&1; do
        sleep 1
        ((elapsed++))
        if [ $elapsed -ge $max_wait ]; then
            log_fail "Timed out waiting for ${service} on port ${port}"
            return 1
        fi
    done
}

call_agent() {
    local method=$1
    local path=$2
    local data=${3:-}
    if [ "$method" = "GET" ]; then
        curl -sf -X GET "${AGENT_BASE_URL}${path}" -H "Content-Type: application/json" 2>/dev/null
    else
        curl -sf -X POST "${AGENT_BASE_URL}${path}" -H "Content-Type: application/json" -d "${data}" 2>/dev/null
    fi
}

restore_service() {
    local service=$1
    log_step "Restoring ${service}..."
    ${COMPOSE_CMD} start "${service}" > /dev/null 2>&1 || true
}

check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check price-alert stack
    if ! curl -sf "http://localhost:9090/-/ready" > /dev/null 2>&1; then
        echo -e "${RED}ERROR: Prometheus not running on :9090${NC}"
        echo "Start price-alert stack first: cd ${PRICE_ALERT_DIR} && ./scripts/launch.sh up"
        exit 1
    fi

    # Check agent
    if ! curl -sf "${AGENT_BASE_URL}/actuator/health" > /dev/null 2>&1; then
        echo -e "${RED}ERROR: oncall-support-agent not running on ${AGENT_BASE_URL}${NC}"
        echo "Start with: ./gradlew bootRun --args='--spring.profiles.active=e2e'"
        exit 1
    fi

    log_info "All prerequisites met"
}

# =============================================================================
# SCENARIO 1: Evaluator Crash
# Expected: UC-1 triage detects zero tick throughput, evaluator unreachable
# =============================================================================
test_evaluator_crash() {
    log_info "SCENARIO 1: Evaluator Crash"

    log_step "Stopping evaluator..."
    ${COMPOSE_CMD} stop evaluator > /dev/null 2>&1

    log_step "Waiting 15s for metrics to reflect the failure..."
    sleep 15

    # UC-4: Health check should show degraded status
    log_step "Running UC-4: Health check on evaluator..."
    local health_response
    health_response=$(call_agent GET "/api/v1/health/evaluator" || echo "FAILED")
    if [ "$health_response" != "FAILED" ]; then
        log_pass "UC-4 health check returned response for crashed evaluator"
    else
        log_fail "UC-4 health check failed for crashed evaluator"
    fi

    # UC-10: SLO should show impact
    log_step "Running UC-10: SLO check on evaluator..."
    local slo_response
    slo_response=$(call_agent GET "/api/v1/slo/evaluator" || echo "FAILED")
    if [ "$slo_response" != "FAILED" ]; then
        log_pass "UC-10 SLO check returned response for crashed evaluator"
    else
        log_fail "UC-10 SLO check failed for crashed evaluator"
    fi

    # UC-1: Triage should detect the issue
    log_step "Running UC-1: Incident triage for evaluator crash..."
    local triage_response
    triage_response=$(call_agent POST "/api/v1/triage" \
        '{"alertId":"e2e-evaluator-crash","service":"evaluator"}' || echo "FAILED")
    if [ "$triage_response" != "FAILED" ]; then
        log_pass "UC-1 triage returned report for evaluator crash"
    else
        log_fail "UC-1 triage failed for evaluator crash"
    fi

    restore_service evaluator
    wait_for_service evaluator 8082 60
    log_info "Scenario 1 complete"
    echo ""
}

# =============================================================================
# SCENARIO 2: Kafka Broker Down
# Expected: Consumer lag spike, throughput drop, KafkaConsumerException in logs
# =============================================================================
test_kafka_down() {
    log_info "SCENARIO 2: Kafka Broker Down (kafka)"

    log_step "Stopping kafka broker..."
    ${COMPOSE_CMD} stop kafka > /dev/null 2>&1

    log_step "Waiting 20s for consumer lag to build..."
    sleep 20

    # UC-2: Log analysis should show Kafka errors
    log_step "Running UC-2: Log analysis on tick-ingestor..."
    local logs_response
    logs_response=$(call_agent POST "/api/v1/log-analysis" \
        '{"service":"tick-ingestor","timeWindowMinutes":5}' || echo "FAILED")
    if [ "$logs_response" != "FAILED" ]; then
        log_pass "UC-2 log analysis returned response during Kafka outage"
    else
        log_fail "UC-2 log analysis failed during Kafka outage"
    fi

    # UC-1: Triage
    log_step "Running UC-1: Incident triage for Kafka failure..."
    local triage_response
    triage_response=$(call_agent POST "/api/v1/triage" \
        '{"alertId":"e2e-kafka-down","service":"tick-ingestor"}' || echo "FAILED")
    if [ "$triage_response" != "FAILED" ]; then
        log_pass "UC-1 triage returned report for Kafka outage"
    else
        log_fail "UC-1 triage failed for Kafka outage"
    fi

    restore_service kafka
    log_step "Waiting 30s for Kafka to recover and rebalance..."
    sleep 30
    log_info "Scenario 2 complete"
    echo ""
}

# =============================================================================
# SCENARIO 3: Redis Down
# Expected: Rate limiting failures on alert-api, Redis connection errors in logs
# =============================================================================
test_redis_down() {
    log_info "SCENARIO 3: Redis Down"

    log_step "Stopping redis..."
    ${COMPOSE_CMD} stop redis > /dev/null 2>&1

    log_step "Generating traffic to trigger rate limit failures..."
    for i in $(seq 1 10); do
        curl -sf -X POST "http://localhost:8080/api/v1/alerts" \
            -H "Content-Type: application/json" \
            -d '{"symbol":"AAPL","thresholdPrice":999,"direction":"ABOVE"}' > /dev/null 2>&1 || true
    done

    log_step "Waiting 10s for errors to accumulate..."
    sleep 10

    # UC-2: Log analysis should show Redis errors
    log_step "Running UC-2: Log analysis on alert-api..."
    local logs_response
    logs_response=$(call_agent POST "/api/v1/log-analysis" \
        '{"service":"alert-api","timeWindowMinutes":5}' || echo "FAILED")
    if [ "$logs_response" != "FAILED" ]; then
        log_pass "UC-2 log analysis returned response during Redis outage"
    else
        log_fail "UC-2 log analysis failed during Redis outage"
    fi

    restore_service redis
    log_step "Waiting 10s for Redis to recover..."
    sleep 10
    log_info "Scenario 3 complete"
    echo ""
}

# =============================================================================
# SCENARIO 4: Error Budget Burn (400/500 errors on alert-api)
# Expected: SLO budget depletion, elevated burn rate
# =============================================================================
test_error_budget_burn() {
    log_info "SCENARIO 4: Error Budget Burn"

    log_step "Sending 200 bad requests to alert-api to burn error budget..."
    for i in $(seq 1 200); do
        curl -sf -X POST "http://localhost:8080/api/v1/alerts" \
            -H "Content-Type: application/json" \
            -d '{}' > /dev/null 2>&1 || true
    done

    log_step "Waiting 20s for metrics to update..."
    sleep 20

    # UC-10: SLO should show budget burn
    log_step "Running UC-10: SLO check on alert-api..."
    local slo_response
    slo_response=$(call_agent GET "/api/v1/slo/alert-api" || echo "FAILED")
    if [ "$slo_response" != "FAILED" ]; then
        log_pass "UC-10 SLO check returned response during error budget burn"
    else
        log_fail "UC-10 SLO check failed during error budget burn"
    fi

    # UC-4: Health check should reflect elevated error rate
    log_step "Running UC-4: Health check on alert-api..."
    local health_response
    health_response=$(call_agent GET "/api/v1/health/alert-api" || echo "FAILED")
    if [ "$health_response" != "FAILED" ]; then
        log_pass "UC-4 health check returned response during error budget burn"
    else
        log_fail "UC-4 health check failed during error budget burn"
    fi

    log_info "Scenario 4 complete (error budget will recover over time)"
    echo ""
}

# =============================================================================
# SCENARIO 5: Cascading Failure (PostgreSQL down)
# Expected: All services fail, blast radius = 5 services
# =============================================================================
test_cascading_failure() {
    log_info "SCENARIO 5: Cascading Failure (PostgreSQL down)"

    log_step "Stopping postgres..."
    ${COMPOSE_CMD} stop postgres > /dev/null 2>&1

    log_step "Waiting 15s for cascade to propagate..."
    sleep 15

    # UC-1: Triage should detect cascading failure
    log_step "Running UC-1: Incident triage for cascading failure..."
    local triage_response
    triage_response=$(call_agent POST "/api/v1/triage" \
        '{"alertId":"e2e-cascade","service":"alert-api"}' || echo "FAILED")
    if [ "$triage_response" != "FAILED" ]; then
        log_pass "UC-1 triage returned report for cascading failure"
    else
        log_fail "UC-1 triage failed for cascading failure"
    fi

    # UC-4: Health check multiple services
    for svc in alert-api evaluator notification-persister; do
        log_step "Running UC-4: Health check on ${svc}..."
        local health_response
        health_response=$(call_agent GET "/api/v1/health/${svc}" || echo "FAILED")
        if [ "$health_response" != "FAILED" ]; then
            log_pass "UC-4 health check returned response for ${svc} during cascade"
        else
            log_fail "UC-4 health check failed for ${svc} during cascade"
        fi
    done

    restore_service postgres
    log_step "Waiting 30s for PostgreSQL and dependent services to recover..."
    sleep 30
    log_info "Scenario 5 complete"
    echo ""
}

# =============================================================================
# SCENARIO 6: Steady State (all healthy)
# Expected: GREEN health, HEALTHY SLO, normal logs
# =============================================================================
test_steady_state() {
    log_info "SCENARIO 6: Steady State (Baseline)"

    # UC-4: Health check all services
    for svc in alert-api evaluator tick-ingestor notification-persister market-feed-simulator; do
        log_step "Running UC-4: Health check on ${svc}..."
        local health_response
        health_response=$(call_agent GET "/api/v1/health/${svc}" || echo "FAILED")
        if [ "$health_response" != "FAILED" ]; then
            log_pass "UC-4 health check returned response for ${svc}"
        else
            log_fail "UC-4 health check failed for ${svc}"
        fi
    done

    # UC-10: SLO check
    log_step "Running UC-10: SLO check on alert-api..."
    local slo_response
    slo_response=$(call_agent GET "/api/v1/slo/alert-api" || echo "FAILED")
    if [ "$slo_response" != "FAILED" ]; then
        log_pass "UC-10 SLO check returned response (steady state)"
    else
        log_fail "UC-10 SLO check failed (steady state)"
    fi

    # UC-2: Log analysis
    log_step "Running UC-2: Log analysis on evaluator..."
    local logs_response
    logs_response=$(call_agent POST "/api/v1/log-analysis" \
        '{"service":"evaluator","timeWindowMinutes":30}' || echo "FAILED")
    if [ "$logs_response" != "FAILED" ]; then
        log_pass "UC-2 log analysis returned response (steady state)"
    else
        log_fail "UC-2 log analysis failed (steady state)"
    fi

    # UC-5: Trace analysis
    log_step "Running UC-5: Trace analysis on alert-api..."
    local trace_response
    trace_response=$(call_agent POST "/api/v1/trace-analysis" \
        '{"service":"alert-api","timeWindowMinutes":10}' || echo "FAILED")
    if [ "$trace_response" != "FAILED" ]; then
        log_pass "UC-5 trace analysis returned response (steady state)"
    else
        log_fail "UC-5 trace analysis failed (steady state)"
    fi

    # UC-3: Deploy impact
    log_step "Running UC-3: Deploy impact on evaluator..."
    local deploy_response
    deploy_response=$(call_agent POST "/api/v1/deploy-impact" \
        '{"service":"evaluator"}' || echo "FAILED")
    if [ "$deploy_response" != "FAILED" ]; then
        log_pass "UC-3 deploy impact returned response (steady state)"
    else
        log_fail "UC-3 deploy impact failed (steady state)"
    fi

    # UC-8: Alert fatigue
    log_step "Running UC-8: Alert fatigue analysis..."
    local fatigue_response
    fatigue_response=$(call_agent GET "/api/v1/alert-fatigue?team=price-alerts&days=7" || echo "FAILED")
    if [ "$fatigue_response" != "FAILED" ]; then
        log_pass "UC-8 alert fatigue returned response (steady state)"
    else
        log_fail "UC-8 alert fatigue failed (steady state)"
    fi

    # UC-9: Post-mortem
    log_step "Running UC-9: Post-mortem draft..."
    local pm_response
    pm_response=$(call_agent POST "/api/v1/postmortem" \
        '{"incidentId":"test-001"}' || echo "FAILED")
    if [ "$pm_response" != "FAILED" ]; then
        log_pass "UC-9 post-mortem returned response (steady state)"
    else
        log_fail "UC-9 post-mortem failed (steady state)"
    fi

    log_info "Scenario 6 complete"
    echo ""
}

# --- Scenario List ---
list_scenarios() {
    echo "Available test scenarios:"
    echo "  steady-state      Baseline test — all services healthy"
    echo "  evaluator         Evaluator crash — docker stop evaluator"
    echo "  kafka             Kafka broker down — docker stop kafka"
    echo "  redis             Redis down — rate limiting failures"
    echo "  error-budget      Error budget burn — 200 bad requests"
    echo "  cascade           Cascading failure — PostgreSQL down"
    echo ""
    echo "Usage:"
    echo "  $0                Run all scenarios"
    echo "  $0 evaluator      Run single scenario"
    echo "  $0 --list         List scenarios"
}

# --- Main ---
main() {
    if [ "${1:-}" = "--list" ]; then
        list_scenarios
        exit 0
    fi

    echo "================================================================="
    echo "  E2E Failure Injection Tests"
    echo "  oncall-support-agent vs price-alert"
    echo "================================================================="
    echo ""

    check_prerequisites

    local scenario="${1:-all}"

    case "$scenario" in
        all)
            test_steady_state
            test_evaluator_crash
            test_kafka_down
            test_redis_down
            test_error_budget_burn
            test_cascading_failure
            ;;
        steady-state)   test_steady_state ;;
        evaluator)      test_evaluator_crash ;;
        kafka)          test_kafka_down ;;
        redis)          test_redis_down ;;
        error-budget)   test_error_budget_burn ;;
        cascade)        test_cascading_failure ;;
        *)
            echo "Unknown scenario: ${scenario}"
            list_scenarios
            exit 1
            ;;
    esac

    echo "================================================================="
    echo -e "  Results: ${GREEN}${PASSED} passed${NC}, ${RED}${FAILED} failed${NC}, ${YELLOW}${SKIPPED} skipped${NC}"
    echo "================================================================="

    [ $FAILED -eq 0 ] && exit 0 || exit 1
}

main "$@"
