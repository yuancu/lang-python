#!/usr/bin/env bash
# Demo 1: Python Runs Inside OpenSearch
# Shows that Python standard library works inside OpenSearch scripts.

set -euo pipefail

OPENSEARCH_URL="${OPENSEARCH_URL:-http://localhost:9200}"

echo "=== Demo 1: Python Standard Library Inside OpenSearch ==="
echo ""

echo "--- Running: import math; math.factorial(10) ---"
curl -s -X POST "$OPENSEARCH_URL/_scripts/python/_execute" \
  -H 'Content-Type: application/json' \
  -d '{
    "script": {
      "source": "import math; math.factorial(10)"
    }
  }' | python3 -m json.tool
echo ""

echo "--- Running: json module ---"
curl -s -X POST "$OPENSEARCH_URL/_scripts/python/_execute" \
  -H 'Content-Type: application/json' \
  -d '{
    "script": {
      "source": "import json; json.dumps({\"hello\": \"OpenSearchCon\", \"year\": 2026})"
    }
  }' | python3 -m json.tool
echo ""

echo "--- Running: re (regex) module ---"
curl -s -X POST "$OPENSEARCH_URL/_scripts/python/_execute" \
  -H 'Content-Type: application/json' \
  -d @- <<'EOF' | python3 -m json.tool
{
  "script": {
    "source": "import re; re.findall(r'\\d+', 'OpenSearch 3.5 released in 2026')"
  }
}
EOF
echo ""

echo "--- Running: collections module ---"
curl -s -X POST "$OPENSEARCH_URL/_scripts/python/_execute" \
  -H 'Content-Type: application/json' \
  -d @- <<'EOF' | python3 -m json.tool
{
  "script": {
    "source": "from collections import Counter; dict(Counter(['a','b','a','c','a','b']))"
  }
}
EOF
echo ""

echo "=== Demo 1 Complete ==="
