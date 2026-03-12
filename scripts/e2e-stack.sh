#!/usr/bin/env bash
# =============================================================================
# E2E Test Stack Manager
# Manages mock services (ArgoCD, PagerDuty, Slack) for E2E testing
#
# Prerequisites: price-alert stack must be running separately
#   cd ../price-alert && ./scripts/launch.sh up
#
# Usage:
#   ./scripts/e2e-stack.sh up        # Start mock services
#   ./scripts/e2e-stack.sh down      # Stop mock services
#   ./scripts/e2e-stack.sh status    # Check service health
#   ./scripts/e2e-stack.sh test      # Run E2E tests (starts mocks if needed)
#   ./scripts/e2e-stack.sh logs      # Tail mock service logs
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DOCKER_DIR="${PROJECT_DIR}/docker"
COMPOSE_CMD="docker compose -f ${DOCKER_DIR}/docker-compose.yml"
PRICE_ALERT_DIR="${PRICE_ALERT_DIR:-/Users/puneethkumarck/Documents/AI/github/price-alert}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
log_pass()  { echo -e "${GREEN}[OK]${NC}    $*"; }
log_fail()  { echo -e "${RED}[FAIL]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }

check_price_alert() {
    log_info "Checking price-alert observability stack..."
    local all_ok=true

    for svc_port in "Prometheus:9090:/-/ready" "Loki:3100:/ready" "Tempo:3200:/ready" "Grafana:3000:/api/health"; do
        IFS=':' read -r name port path <<< "$svc_port"
        if curl -sf "http://localhost:${port}${path}" > /dev/null 2>&1; then
            log_pass "${name} on :${port}"
        else
            log_fail "${name} not reachable on :${port}"
            all_ok=false
        fi
    done

    if [ "$all_ok" = false ]; then
        echo ""
        log_warn "Some price-alert services are not running."
        echo "       Start with: cd ${PRICE_ALERT_DIR} && ./scripts/launch.sh up"
        echo ""
    fi
}

check_mocks() {
    log_info "Checking mock services..."

    for svc_port in "ArgoCD:8100" "PagerDuty:8101" "Slack:8102"; do
        IFS=':' read -r name port <<< "$svc_port"
        if curl -sf "http://localhost:${port}/__admin/health" > /dev/null 2>&1; then
            log_pass "Mock ${name} on :${port}"
        else
            log_fail "Mock ${name} not reachable on :${port}"
        fi
    done
}

cmd_up() {
    log_info "Starting mock services (ArgoCD, PagerDuty, Slack)..."
    ${COMPOSE_CMD} up -d
    log_info "Waiting for services to be healthy..."
    sleep 3
    check_mocks
    echo ""
    echo "Mock services ready. E2E config URLs:"
    echo "  ArgoCD:    http://localhost:8100"
    echo "  PagerDuty: http://localhost:8101"
    echo "  Slack:     http://localhost:8102"
    echo ""
    echo "Run E2E tests with:"
    echo "  ./gradlew e2eTest"
    echo ""
    echo "Or start the agent with:"
    echo "  ./gradlew bootRun --args='--spring.profiles.active=e2e'"
}

cmd_down() {
    log_info "Stopping mock services..."
    ${COMPOSE_CMD} down
    log_pass "Mock services stopped"
}

cmd_status() {
    echo "================================================================="
    echo "  E2E Test Stack Status"
    echo "================================================================="
    echo ""
    check_price_alert
    echo ""
    check_mocks
    echo ""

    # Verify WireMock stubs
    for svc_port in "ArgoCD:8100" "PagerDuty:8101" "Slack:8102"; do
        IFS=':' read -r name port <<< "$svc_port"
        local count
        count=$(curl -sf "http://localhost:${port}/__admin/mappings" 2>/dev/null | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('mappings',[])))" 2>/dev/null || echo "0")
        echo "  ${name} stub mappings: ${count}"
    done
    echo ""
}

cmd_test() {
    # Start mocks if not running
    if ! curl -sf "http://localhost:8100/__admin/health" > /dev/null 2>&1; then
        cmd_up
    fi

    log_info "Running E2E tests..."
    cd "${PROJECT_DIR}"
    ./gradlew e2eTest
}

cmd_logs() {
    ${COMPOSE_CMD} logs -f
}

# --- Main ---
case "${1:-help}" in
    up)      cmd_up ;;
    down)    cmd_down ;;
    status)  cmd_status ;;
    test)    cmd_test ;;
    logs)    cmd_logs ;;
    *)
        echo "Usage: $0 {up|down|status|test|logs}"
        echo ""
        echo "Commands:"
        echo "  up      Start mock ArgoCD + PagerDuty + Slack (WireMock)"
        echo "  down    Stop mock services"
        echo "  status  Check all services (price-alert + mocks)"
        echo "  test    Start mocks (if needed) + run ./gradlew e2eTest"
        echo "  logs    Tail mock service logs"
        ;;
esac
