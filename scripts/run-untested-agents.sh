#!/bin/bash
set -uo pipefail

LOG="/Users/puneethkumarck/Documents/AI/github/oncall-support-agent/test-results/untested-agents-results.log"
BASE="http://localhost:8090"

echo "=== Untested Agents: Triage, Deploy Impact, Post-Mortem ===" > "$LOG"
echo "Date: $(date)" >> "$LOG"
echo "" >> "$LOG"

#############################################
# Test 5: IncidentTriageAgent
#############################################
echo "========================================" >> "$LOG"
echo "## Test 5: IncidentTriageAgent" >> "$LOG"
echo "========================================" >> "$LOG"
echo "Timestamp: $(date)" >> "$LOG"
echo ""
echo "=== Test 5: IncidentTriageAgent ==="
echo "Using incident Q0OWE7FWN731HY (tick-ingestor is down, triggered)"

echo "--- Request ---" >> "$LOG"
echo 'POST /api/v1/triage' >> "$LOG"
echo '{"alertId":"Q0OWE7FWN731HY","service":"tick-ingestor","severity":"SEV1","description":"tick-ingestor is down"}' >> "$LOG"
echo "" >> "$LOG"

result=$(curl -s --max-time 180 -X POST "${BASE}/api/v1/triage" \
  -H "Content-Type: application/json" \
  -d '{"alertId":"Q0OWE7FWN731HY","service":"tick-ingestor","severity":"SEV1","description":"tick-ingestor is down"}' 2>&1)
bytes=${#result}
echo "--- Response ($bytes bytes) ---" >> "$LOG"
echo "$result" >> "$LOG"
echo "" >> "$LOG"

if [ "$bytes" -gt 10 ]; then
    echo "STATUS: PASS ($bytes bytes)" >> "$LOG"
    echo "  PASS Test 5: IncidentTriageAgent ($bytes bytes)"
else
    echo "STATUS: FAIL (empty or error)" >> "$LOG"
    echo "  FAIL Test 5: IncidentTriageAgent"
fi
echo "" >> "$LOG"
echo "---END 5---" >> "$LOG"
echo "" >> "$LOG"

#############################################
# Test 6: DeployImpactAgent
#############################################
echo "========================================" >> "$LOG"
echo "## Test 6: DeployImpactAgent" >> "$LOG"
echo "========================================" >> "$LOG"
echo "Timestamp: $(date)" >> "$LOG"
echo ""
echo "=== Test 6: DeployImpactAgent ==="
echo "Using service alert-api"

echo "--- Request ---" >> "$LOG"
echo 'POST /api/v1/deploy-impact' >> "$LOG"
echo '{"service":"alert-api"}' >> "$LOG"
echo "" >> "$LOG"

result=$(curl -s --max-time 180 -X POST "${BASE}/api/v1/deploy-impact" \
  -H "Content-Type: application/json" \
  -d '{"service":"alert-api"}' 2>&1)
bytes=${#result}
echo "--- Response ($bytes bytes) ---" >> "$LOG"
echo "$result" >> "$LOG"
echo "" >> "$LOG"

if [ "$bytes" -gt 10 ]; then
    echo "STATUS: PASS ($bytes bytes)" >> "$LOG"
    echo "  PASS Test 6: DeployImpactAgent ($bytes bytes)"
else
    echo "STATUS: FAIL (empty or error)" >> "$LOG"
    echo "  FAIL Test 6: DeployImpactAgent"
fi
echo "" >> "$LOG"
echo "---END 6---" >> "$LOG"
echo "" >> "$LOG"

#############################################
# Test 8: PostMortemAgent
#############################################
echo "========================================" >> "$LOG"
echo "## Test 8: PostMortemAgent" >> "$LOG"
echo "========================================" >> "$LOG"
echo "Timestamp: $(date)" >> "$LOG"
echo ""
echo "=== Test 8: PostMortemAgent ==="
echo "Using incident Q1FTK14KE1M4HC (Evaluator stopped processing ticks, resolved)"

echo "--- Request ---" >> "$LOG"
echo 'POST /api/v1/postmortem' >> "$LOG"
echo '{"incidentId":"Q1FTK14KE1M4HC","service":"evaluator"}' >> "$LOG"
echo "" >> "$LOG"

result=$(curl -s --max-time 180 -X POST "${BASE}/api/v1/postmortem" \
  -H "Content-Type: application/json" \
  -d '{"incidentId":"Q1FTK14KE1M4HC","service":"evaluator"}' 2>&1)
bytes=${#result}
echo "--- Response ($bytes bytes) ---" >> "$LOG"
echo "$result" >> "$LOG"
echo "" >> "$LOG"

if [ "$bytes" -gt 10 ]; then
    echo "STATUS: PASS ($bytes bytes)" >> "$LOG"
    echo "  PASS Test 8: PostMortemAgent ($bytes bytes)"
else
    echo "STATUS: FAIL (empty or error)" >> "$LOG"
    echo "  FAIL Test 8: PostMortemAgent"
fi
echo "" >> "$LOG"
echo "---END 8---" >> "$LOG"
echo "" >> "$LOG"

echo "=== All Untested Agents Complete ===" >> "$LOG"
echo "Date: $(date)" >> "$LOG"

echo ""
echo "ALL DONE — Results in: $LOG"
