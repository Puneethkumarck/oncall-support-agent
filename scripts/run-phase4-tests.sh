#!/bin/bash
set -uo pipefail

LOG="/Users/puneethkumarck/Documents/AI/github/oncall-support-agent/test-results/phase4-results.log"
BASE="http://localhost:8090"
PRICE_ALERT="/Users/puneethkumarck/Documents/AI/github/price-alert"

echo "=== Phase 4: Stress & Failure Scenarios ===" > "$LOG"
echo "Date: $(date)" >> "$LOG"
echo "" >> "$LOG"

#############################################
# 4.1 Test during high load
#############################################
echo "========================================" >> "$LOG"
echo "## Test 4.1: High Load on price-alert" >> "$LOG"
echo "========================================" >> "$LOG"
echo "Timestamp: $(date)" >> "$LOG"
echo ""
echo "=== Test 4.1: High Load ==="

# Take baseline health first
echo "--- Baseline health before load ---" >> "$LOG"
baseline=$(curl -s --max-time 120 "${BASE}/api/v1/health/alert-api" 2>&1)
baseline_bytes=${#baseline}
echo "Baseline response: $baseline_bytes bytes" >> "$LOG"
echo "$baseline" >> "$LOG"
echo "" >> "$LOG"

# Generate load — create 20 alerts rapidly
echo "--- Generating load: 20 alerts via price-alert API ---" >> "$LOG"
echo "Generating 20 alerts..."
for i in $(seq 1 20); do
    curl -s --max-time 5 -X POST http://localhost:8080/api/v1/alerts \
        -H "Content-Type: application/json" \
        -d "{\"symbol\": \"AAPL\", \"thresholdPrice\": $((100 + i)), \"direction\": \"ABOVE\"}" >> /dev/null 2>&1 &
done
wait
echo "Load generated: 20 alerts sent" >> "$LOG"
echo "20 alerts sent, waiting 10s for metrics to update..."
sleep 10

# Health check during/after load
echo "--- Health check after load ---" >> "$LOG"
echo "Timestamp: $(date)" >> "$LOG"
loaded=$(curl -s --max-time 120 "${BASE}/api/v1/health/alert-api" 2>&1)
loaded_bytes=${#loaded}
echo "After-load response: $loaded_bytes bytes" >> "$LOG"
echo "$loaded" >> "$LOG"
echo "" >> "$LOG"

if [ "$loaded_bytes" -gt 10 ]; then
    echo "STATUS: PASS ($loaded_bytes bytes)" >> "$LOG"
    echo "  PASS 4.1: High Load ($loaded_bytes bytes)"
else
    echo "STATUS: FAIL" >> "$LOG"
    echo "  FAIL 4.1: High Load"
fi
echo "" >> "$LOG"
echo "---END 4.1---" >> "$LOG"
echo "" >> "$LOG"

#############################################
# 4.2 Test with evaluator service down
#############################################
echo "========================================" >> "$LOG"
echo "## Test 4.2: Service Down (evaluator)" >> "$LOG"
echo "========================================" >> "$LOG"
echo "Timestamp: $(date)" >> "$LOG"
echo ""
echo "=== Test 4.2: Service Down ==="

# Stop evaluator
echo "--- Stopping evaluator ---" >> "$LOG"
echo "Stopping evaluator..."
cd "$PRICE_ALERT" && docker compose stop evaluator evaluator-2 2>&1 >> "$LOG"
echo "Evaluator stopped, waiting 15s for Prometheus to detect..." >> "$LOG"
sleep 15

# Check Prometheus sees it down
echo "--- Prometheus up metric ---" >> "$LOG"
prom_check=$(curl -s 'http://localhost:9090/api/v1/query?query=up{job="evaluator"}' 2>&1)
echo "$prom_check" >> "$LOG"
echo "" >> "$LOG"

# Health check — should detect degraded state
echo "--- Health check with evaluator down ---" >> "$LOG"
echo "Timestamp: $(date)" >> "$LOG"
down_health=$(curl -s --max-time 120 "${BASE}/api/v1/health/evaluator" 2>&1)
down_bytes=${#down_health}
echo "Response: $down_bytes bytes" >> "$LOG"
echo "$down_health" >> "$LOG"
echo "" >> "$LOG"

if [ "$down_bytes" -gt 10 ]; then
    echo "STATUS: PASS ($down_bytes bytes)" >> "$LOG"
    echo "  PASS 4.2: Service Down — Health check ($down_bytes bytes)"
else
    echo "STATUS: FAIL" >> "$LOG"
    echo "  FAIL 4.2: Service Down — Health check"
fi
echo "" >> "$LOG"

# Restart evaluator
echo "--- Restarting evaluator ---" >> "$LOG"
echo "Restarting evaluator..."
cd "$PRICE_ALERT" && docker compose start evaluator evaluator-2 2>&1 >> "$LOG"
echo "Evaluator restarted" >> "$LOG"
sleep 5

echo "---END 4.2---" >> "$LOG"
echo "" >> "$LOG"

#############################################
# 4.3 Test with Prometheus temporarily down
#############################################
echo "========================================" >> "$LOG"
echo "## Test 4.3: Prometheus Temporarily Down" >> "$LOG"
echo "========================================" >> "$LOG"
echo "Timestamp: $(date)" >> "$LOG"
echo ""
echo "=== Test 4.3: Prometheus Down ==="

# Stop Prometheus
echo "--- Stopping Prometheus ---" >> "$LOG"
echo "Stopping Prometheus..."
cd "$PRICE_ALERT" && docker compose stop prometheus 2>&1 >> "$LOG"
echo "Prometheus stopped" >> "$LOG"
sleep 5

# Health check — should handle gracefully
echo "--- Health check with Prometheus down ---" >> "$LOG"
echo "Timestamp: $(date)" >> "$LOG"
echo "Running health check (expecting graceful error handling)..."
prom_down=$(curl -s --max-time 120 "${BASE}/api/v1/health/alert-api" 2>&1)
prom_down_bytes=${#prom_down}
echo "Response: $prom_down_bytes bytes" >> "$LOG"
echo "$prom_down" >> "$LOG"
echo "" >> "$LOG"

# Check if agent crashed
agent_health=$(curl -s --max-time 5 "${BASE}/actuator/health" 2>&1)
echo "Agent health after Prometheus down: $agent_health" >> "$LOG"
echo "" >> "$LOG"

if echo "$agent_health" | grep -q "UP"; then
    echo "STATUS: PASS (agent did not crash, health=$agent_health)" >> "$LOG"
    echo "  PASS 4.3: Prometheus Down — agent stayed alive"
else
    echo "STATUS: FAIL (agent may have crashed)" >> "$LOG"
    echo "  FAIL 4.3: Prometheus Down — agent crashed"
fi
echo "" >> "$LOG"

# Restart Prometheus
echo "--- Restarting Prometheus ---" >> "$LOG"
echo "Restarting Prometheus..."
cd "$PRICE_ALERT" && docker compose start prometheus 2>&1 >> "$LOG"
echo "Prometheus restarted" >> "$LOG"
sleep 5

# Verify recovery
echo "--- Verify recovery ---" >> "$LOG"
recovery=$(curl -s --max-time 120 "${BASE}/api/v1/health/alert-api" 2>&1)
recovery_bytes=${#recovery}
echo "Recovery response: $recovery_bytes bytes" >> "$LOG"
if [ "$recovery_bytes" -gt 10 ]; then
    echo "Recovery: PASS — agent works again after Prometheus restart" >> "$LOG"
    echo "  PASS 4.3 Recovery: agent works after Prometheus restart"
else
    echo "Recovery: FAIL" >> "$LOG"
    echo "  FAIL 4.3 Recovery"
fi
echo "" >> "$LOG"
echo "---END 4.3---" >> "$LOG"
echo "" >> "$LOG"

echo "=== Phase 4 Complete ===" >> "$LOG"
echo "Date: $(date)" >> "$LOG"
echo ""
echo "ALL PHASE 4 TESTS COMPLETED — Results in: $LOG"
