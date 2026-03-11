#!/usr/bin/env bash
# Demo 2: Custom Scoring with Python + NumPy
# Creates a books index, shows Python scoring with stdlib,
# then uses NumPy for advanced statistical scoring.

set -euo pipefail

OPENSEARCH_URL="${OPENSEARCH_URL:-http://localhost:9200}"
INDEX="books"

echo "=== Demo 2: Custom Scoring with Python + NumPy ==="
echo ""

# --- Setup ---

echo "--- Creating index: $INDEX ---"
curl -s -X DELETE "$OPENSEARCH_URL/$INDEX" > /dev/null 2>&1 || true
curl -s -X PUT "$OPENSEARCH_URL/$INDEX" \
  -H 'Content-Type: application/json' \
  -d @- <<'EOF' | python3 -m json.tool
{
  "mappings": {
    "properties": {
      "title":   { "type": "text" },
      "ratings": { "type": "integer" }
    }
  }
}
EOF
echo ""

echo "--- Indexing sample books ---"
curl -s -X POST "$OPENSEARCH_URL/_bulk?refresh=true" \
  -H 'Content-Type: application/x-ndjson' \
  -d '
{"index": {"_index": "books", "_id": "1"}}
{"title": "The Great Gatsby", "ratings": [5, 5, 5]}
{"index": {"_index": "books", "_id": "2"}}
{"title": "1984", "ratings": [4, 3, 5]}
{"index": {"_index": "books", "_id": "3"}}
{"title": "Brave New World", "ratings": [2, 1, 5]}
{"index": {"_index": "books", "_id": "4"}}
{"title": "Dune", "ratings": [5, 4, 5, 4, 5, 4, 3]}
{"index": {"_index": "books", "_id": "5"}}
{"title": "Fahrenheit 451", "ratings": [3, 3, 3, 3, 3]}
' > /dev/null
echo "Indexed 5 books."
echo ""

# ============================================================
# Part 1: Simple scoring with Python stdlib
# ============================================================

echo "========================================"
echo "  Part 1: Scoring with Python stdlib"
echo "========================================"
echo ""

echo "--- Storing script: avg_ratings ---"
echo "    sum(doc['ratings']) / len(doc['ratings']) * params['multiplier']"
curl -s -X POST "$OPENSEARCH_URL/_scripts/avg_ratings" \
  -H 'Content-Type: application/json' \
  -d @- <<'EOF' | python3 -m json.tool
{
  "script": {
    "lang": "python",
    "source": "sum(doc['ratings']) / len(doc['ratings']) * params['multiplier']"
  }
}
EOF
echo ""

echo "--- Running function_score query (multiplier=2.0) ---"
curl -s -X GET "$OPENSEARCH_URL/$INDEX/_search" \
  -H 'Content-Type: application/json' \
  -d @- <<'EOF' | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(f\"{'Book':<25} {'Ratings':<25} {'Avg':>5} {'Score (x2)':>10}\")
print('-' * 70)
for hit in data['hits']['hits']:
    title = hit['_source']['title']
    ratings = hit['_source']['ratings']
    avg = sum(ratings) / len(ratings)
    score = hit['_score']
    print(f'{title:<25} {str(ratings):<25} {avg:>5.2f} {score:>10.2f}')
"
{
  "query": {
    "function_score": {
      "script_score": {
        "script": {
          "id": "avg_ratings",
          "params": { "multiplier": 2.0 }
        }
      }
    }
  }
}
EOF
echo ""

# ============================================================
# Part 2: Advanced scoring with NumPy
# ============================================================

echo "========================================"
echo "  Part 2: Scoring with NumPy"
echo "========================================"
echo ""

echo "--- Verifying NumPy is available ---"
curl -s -X POST "$OPENSEARCH_URL/_scripts/python/_execute" \
  -H 'Content-Type: application/json' \
  -d @- <<'EOF' | python3 -m json.tool
{
  "script": {
    "source": "import numpy as np; f'NumPy {np.__version__} loaded'"
  }
}
EOF
echo ""

echo "--- Storing script: numpy_rating_score ---"
echo "    Uses NumPy to compute a confidence-weighted score:"
echo "    score = mean * (1 - std/5) * log2(count + 1)"
echo "    Books with more consistent, higher ratings rank better."
echo ""
curl -s -X POST "$OPENSEARCH_URL/_scripts/numpy_rating_score" \
  -H 'Content-Type: application/json' \
  -d @- <<'EOF' | python3 -m json.tool
{
  "script": {
    "lang": "python",
    "source": "import numpy as np\n\narr = np.array(doc['ratings'], dtype=float)\nmean = np.mean(arr)\nstd = np.std(arr)\ncount = len(arr)\n\n# Confidence-weighted score:\n#   - Higher mean = better\n#   - Lower std = more consistent = bonus\n#   - More ratings = more confidence (log scale)\nfloat(mean * (1 - std / 5) * np.log2(count + 1))"
  }
}
EOF
echo ""

echo "--- Running NumPy-powered scoring query ---"
curl -s -X GET "$OPENSEARCH_URL/$INDEX/_search" \
  -H 'Content-Type: application/json' \
  -d @- <<'EOF' | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(f\"{'Book':<25} {'Ratings':<25} {'Mean':>5} {'Std':>5} {'Count':>5} {'Score':>8}\")
print('-' * 80)
for hit in data['hits']['hits']:
    title = hit['_source']['title']
    ratings = hit['_source']['ratings']
    import numpy as np
    arr = np.array(ratings, dtype=float)
    print(f'{title:<25} {str(ratings):<25} {np.mean(arr):>5.2f} {np.std(arr):>5.2f} {len(arr):>5} {hit[\"_score\"]:>8.2f}')
"
{
  "query": {
    "function_score": {
      "script_score": {
        "script": {
          "id": "numpy_rating_score"
        }
      }
    }
  }
}
EOF
echo ""

echo "--- Why NumPy? ---"
echo "  The confidence-weighted score uses np.mean, np.std, np.log2"
echo "  — operations that would require manual loops in Painless."
echo "  NumPy makes complex ranking formulas readable and concise."
echo ""

echo "=== Demo 2 Complete ==="
