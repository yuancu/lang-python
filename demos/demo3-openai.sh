#!/usr/bin/env bash
# Demo 3: AI-Powered Script Fields with OpenAI
#
# Creates an index of factual questions, then uses a Python script field
# to call OpenAI's chat completion API directly and answer each question
# at query time.
#
# Requires: python.PosixModuleBackend=native in the plugin's context config
# so that urllib.request can make outbound HTTP(S) calls.

set -euo pipefail

OPENSEARCH_URL="${OPENSEARCH_URL:-http://localhost:9200}"
OPENAI_API_KEY="${OPENAI_API_KEY:?Please set OPENAI_API_KEY environment variable}"
INDEX="trivia"

echo "=== Demo 3: AI-Powered Script Fields with OpenAI ==="
echo ""

# --- Create trivia index ---

echo "--- Creating index: $INDEX ---"
curl -s -X DELETE "$OPENSEARCH_URL/$INDEX" > /dev/null 2>&1 || true
curl -s -X PUT "$OPENSEARCH_URL/$INDEX" \
  -H 'Content-Type: application/json' \
  -d @- <<'EOF' | python3 -m json.tool
{
  "mappings": {
    "properties": {
      "question": { "type": "text", "fields": { "keyword": { "type": "keyword" } } },
      "category": { "type": "keyword" }
    }
  }
}
EOF
echo ""

echo "--- Indexing sample questions ---"
curl -s -X POST "$OPENSEARCH_URL/_bulk?refresh=true" \
  -H 'Content-Type: application/x-ndjson' \
  -d '
{"index": {"_index": "trivia", "_id": "1"}}
{"question": "What is the capital of France?", "category": "geography"}
{"index": {"_index": "trivia", "_id": "2"}}
{"question": "What is the speed of light in km/s?", "category": "science"}
{"index": {"_index": "trivia", "_id": "3"}}
{"question": "Who wrote Romeo and Juliet?", "category": "literature"}
'
echo ""

# --- Query with Python script field ---

echo "--- Querying with Python script field (calling OpenAI for each doc) ---"
echo "    The Python script calls OpenAI chat completions API directly."
echo ""

# The Python script:
#   1. Reads the question from doc['question.keyword']
#   2. Calls OpenAI chat completion API via urllib.request
#   3. Parses the JSON response and returns the answer
#
# params['api_key'] is passed at query time so the key is never stored.

curl -s -X GET "$OPENSEARCH_URL/$INDEX/_search" \
  -H 'Content-Type: application/json' \
  -d @- <<EOF | python3 -m json.tool
{
  "query": { "match_all": {} },
  "_source": ["question", "category"],
  "script_fields": {
    "ai_answer": {
      "script": {
        "lang": "python",
        "source": "import json, urllib.request\n\nquestion = doc['question.keyword'][0]\n\nbody = json.dumps({\n    'model': 'gpt-4o-mini',\n    'messages': [\n        {'role': 'system', 'content': 'Answer the question in one short sentence.'},\n        {'role': 'user', 'content': question}\n    ],\n    'max_tokens': 50,\n    'temperature': 0\n}).encode()\n\nreq = urllib.request.Request(\n    'https://api.openai.com/v1/chat/completions',\n    data=body,\n    headers={\n        'Content-Type': 'application/json',\n        'Authorization': 'Bearer ' + params['api_key']\n    }\n)\nresp = urllib.request.urlopen(req)\nresult = json.loads(resp.read())\nresult['choices'][0]['message']['content']",
        "params": {
          "api_key": "${OPENAI_API_KEY}"
        }
      }
    }
  }
}
EOF
echo ""

# --- Pretty-print results ---

echo "--- Results Summary ---"
curl -s -X GET "$OPENSEARCH_URL/$INDEX/_search" \
  -H 'Content-Type: application/json' \
  -d @- <<EOF | python3 -c "
import sys, json
data = json.load(sys.stdin)
for hit in data['hits']['hits']:
    q = hit['_source']['question']
    cat = hit['_source']['category']
    answer = hit['fields'].get('ai_answer', ['N/A'])[0]
    print(f'[{cat}] Q: {q}')
    print(f'        A: {answer}')
    print()
"
{
  "query": { "match_all": {} },
  "_source": ["question", "category"],
  "script_fields": {
    "ai_answer": {
      "script": {
        "lang": "python",
        "source": "import json, urllib.request\n\nquestion = doc['question.keyword'][0]\n\nbody = json.dumps({\n    'model': 'gpt-4o-mini',\n    'messages': [\n        {'role': 'system', 'content': 'Answer the question in one short sentence.'},\n        {'role': 'user', 'content': question}\n    ],\n    'max_tokens': 50,\n    'temperature': 0\n}).encode()\n\nreq = urllib.request.Request(\n    'https://api.openai.com/v1/chat/completions',\n    data=body,\n    headers={\n        'Content-Type': 'application/json',\n        'Authorization': 'Bearer ' + params['api_key']\n    }\n)\nresp = urllib.request.urlopen(req)\nresult = json.loads(resp.read())\nresult['choices'][0]['message']['content']",
        "params": {
          "api_key": "${OPENAI_API_KEY}"
        }
      }
    }
  }
}
EOF
echo ""

echo "=== Demo 3 Complete ==="
