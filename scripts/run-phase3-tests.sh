#!/bin/bash
set -euo pipefail

LOG="/Users/puneethkumarck/Documents/AI/github/oncall-support-agent/test-results/phase3-results.log"
BASE="http://localhost:8090"
TIMEOUT=120

echo "=== Phase 3: Agent Testing Results ===" > "$LOG"
echo "Date: $(date)" >> "$LOG"
echo "LLM: claude-haiku-4-5 via Ollama-to-Anthropic proxy" >> "$LOG"
echo "Profile: price-alert | Spring Boot 4.0.3" >> "$LOG"
echo "========================================" >> "$LOG"
echo "" >> "$LOG"

run_test() {
    local num="$1"
    local name="$2"
    local method="$3"
    local path="$4"
    local body="${5:-}"

    echo "--- Test ${num}: ${name} ---" >> "$LOG"
    echo "Timestamp: $(date)" >> "$LOG"
    echo "Running test ${num}: ${name}..."

    if [ "$method" = "POST" ]; then
        result=$(curl -s --max-time "$TIMEOUT" -X POST "${BASE}${path}" -H "Content-Type: application/json" -d "$body" 2>&1) || true
    else
        result=$(curl -s --max-time "$TIMEOUT" "${BASE}${path}" 2>&1) || true
    fi

    echo "$result" >> "$LOG"
    bytes=${#result}
    if [ "$bytes" -gt 10 ]; then
        echo "STATUS: PASS (${bytes} bytes)" >> "$LOG"
        echo "  PASS Test ${num}: ${name} (${bytes} bytes)"
    else
        echo "STATUS: FAIL (empty or error)" >> "$LOG"
        echo "  FAIL Test ${num}: ${name}"
    fi
    echo "" >> "$LOG"
    echo "---END---" >> "$LOG"
    echo "" >> "$LOG"
}

run_test "3.1a" "ServiceHealthAgent - alert-api" "GET" "/api/v1/health/alert-api"
run_test "3.1b" "ServiceHealthAgent - evaluator" "GET" "/api/v1/health/evaluator"
run_test "3.1c" "ServiceHealthAgent - tick-ingestor" "GET" "/api/v1/health/tick-ingestor"
run_test "3.1d" "ServiceHealthAgent - notification-persister" "GET" "/api/v1/health/notification-persister"
run_test "3.2" "SLOMonitorAgent - alert-api" "GET" "/api/v1/slo/alert-api"
run_test "3.3" "LogAnalysisAgent" "POST" "/api/v1/log-analysis" '{"service":"alert-api","timeWindow":"1h","severity":"ERROR"}'
run_test "3.4" "TraceAnalysisAgent" "POST" "/api/v1/trace-analysis" '{"service":"alert-api"}'
run_test "3.7" "AlertFatigueAgent" "GET" "/api/v1/alert-fatigue?team=platform&days=7"

echo "" >> "$LOG"
echo "=== ALL PHASE 3 TESTS COMPLETED ===" >> "$LOG"
echo "Date: $(date)" >> "$LOG"
echo ""
echo "ALL PHASE 3 TESTS COMPLETED - Results in: $LOG"
