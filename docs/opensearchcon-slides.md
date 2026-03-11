---
marp: true
theme: default
paginate: true
style: |
  section {
    font-family: 'Helvetica Neue', Arial, sans-serif;
    padding: 40px 60px;
  }
  h1 {
    color: #003B5C;
    font-size: 2.2em;
  }
  h2 {
    color: #005EB8;
    font-size: 1.6em;
  }
  code {
    background: #f4f4f4;
    border-radius: 4px;
  }
  pre {
    background: #fafafa;
    color: #333;
    border: 1px solid #e0e0e0;
    border-radius: 8px;
    padding: 20px;
    font-size: 0.75em;
  }
  pre code {
    background: transparent;
    color: inherit;
  }
  table {
    font-size: 0.85em;
  }
  .columns {
    display: flex;
    gap: 40px;
  }
  .col {
    flex: 1;
  }
  .highlight {
    color: #005EB8;
    font-weight: bold;
  }
  .dimmed {
    color: #888;
    font-size: 0.85em;
  }
  footer {
    color: #999;
    font-size: 0.6em;
  }
---

<!-- _paginate: false -->

# Python on OpenSearch

### Bringing Native Python Scripting to Search and Analytics

<br>

**Yuanchun Shen & Shuang Li**
OpenSearchCon China 2026

<br>

![h:60](https://opensearch.org/assets/brand/SVG/Mark/opensearch_mark_default.svg)

---

# Why Python?

OpenSearch scripting today means **Painless** — a purpose-built language.

But most developers already know Python.

<br>

> What if you could write OpenSearch scripts in the language you already use every day?

---

# The Same Task: Painless vs Python

<div class="columns">
<div class="col">

**Painless**
```java
String first = doc['first_name.keyword'].value;
String last = doc['last_name.keyword'].value;
return first + ' ' + last;
```

</div>
<div class="col">

**Python**
```python
doc['first_name.keyword'][0]
  + ' '
  + doc['last_name.keyword'][0]
```

</div>
</div>

<br>

Similar here. But what about **libraries**, **string methods**, **list comprehensions**?

---

# When Painless Isn't Enough

<div class="columns">
<div class="col">

**Painless**
```java
// Average ratings? Manual loop.
double sum = 0;
for (int i = 0;
     i < doc['ratings'].length; i++) {
  sum += doc['ratings'][i];
}
return sum / doc['ratings'].length
         * params['multiplier'];
```

</div>
<div class="col">

**Python**
```python
sum(doc['ratings'])
  / len(doc['ratings'])
  * params['multiplier']
```

</div>
</div>

<br>

Python is more concise — and this is still a simple case.

What about regex, JSON parsing, NLP, calling an ML service?

---

# What We Built

A **Python Language Plugin** for OpenSearch

- Write scripts in Python for **ingest**, **search**, **scoring**, and **field** contexts
- Use Python standard library: `math`, `json`, `re`, `collections`, ...
- Import third-party libraries like **NumPy**
- ~1,400 lines of custom code (the rest is generated grammar)

<br>

Let me show you.

---

<!-- _class: "" -->

# Demo 1: Python Runs Inside OpenSearch

```bash
POST /_scripts/python/_execute
{
  "script": {
    "source": "import math; math.factorial(10)"
  }
}
```

```json
{ "result": "3628800" }
```

<br>

**Standard library, no extra setup.** `math`, `json`, `re`, `collections` — all available.

<!-- Live demo -->

---

# Demo 2: Custom Scoring with Python + NumPy

**Part 1** — Simple scoring with Python stdlib:

```python
# Stored script: average rating × multiplier
sum(doc['ratings']) / len(doc['ratings']) * params['multiplier']
```

**Part 2** — Confidence-weighted scoring with NumPy:

```python
import numpy as np
arr = np.array(doc['ratings'], dtype=float)
# Higher mean + lower variance + more ratings = better
float(mean * (1 - std / 5) * np.log2(count + 1))
```

<!-- Live demo: show both scoring approaches -->

---

# Demo 2: Results — Simple vs NumPy Scoring

| Book | Ratings | Simple (×2) | NumPy Score |
|------|---------|-------------|-------------|
| The Great Gatsby | [5, 5, 5] | **10.0** | 10.0 |
| Dune | [5,4,5,4,5,4,3] | 8.57 | **11.06** |
| Fahrenheit 451 | [3, 3, 3, 3, 3] | 6.0 | 7.75 |
| 1984 | [4, 3, 5] | 8.0 | 6.69 |

**Dune** jumps to #1 with NumPy — more ratings + consistency matters.

NumPy makes complex ranking formulas readable: `np.mean`, `np.std`, `np.log2`.

---

# Demo 3: AI-Powered Script Fields with OpenAI

**Scenario**: Answer trivia questions at query time using OpenAI

```python
import json, urllib.request

question = doc['question.keyword'][0]
body = json.dumps({
    'model': 'gpt-4o-mini',
    'messages': [{'role': 'user', 'content': question}],
    'max_tokens': 50}).encode()

req = urllib.request.Request(
    'https://api.openai.com/v1/chat/completions',
    data=body,
    headers={'Authorization': 'Bearer ' + params['api_key']}
)
resp = urllib.request.urlopen(req)
json.loads(resp.read())['choices'][0]['message']['content']
```

<!-- Live demo -->

---

# How It Works

```
┌──────────────┐     ┌──────────────┐     ┌───────────────────┐
│ Python Code  │────>│ ANTLR4       │────>│ Semantic          │
│ (user script)│     │ Parser       │     │ Analyzer          │
└──────────────┘     └──────────────┘     │ (loop detection)  │
                                          └────────┬──────────┘
                                                   │
                                                   ▼
┌─────────────┐     ┌──────────────┐     ┌──────────────────┐
│ Result      │<────│ Extract      │<────│ GraalVM Polyglot │
│ (to caller) │     │ Value        │     │ Context (Python) │
└─────────────┘     └──────────────┘     └──────────────────┘
```

Python runs **inside the JVM** via GraalVM — no subprocess, no network call.

---

# GraalVM: The Key Enabler

**GraalVM Polyglot** allows multiple languages to coexist on the JVM.

- Python code is interpreted by **GraalPy** (GraalVM's Python implementation)
- Java objects (`doc`, `params`, `ctx`) are passed directly to Python — no serialization
- Each script execution gets a **fresh, isolated context**

```java
// Simplified: how the plugin executes Python
try (Context context = Context.newBuilder("python")
        .sandbox(SandboxPolicy.TRUSTED)
        .build()) {
    context.getBindings("python").putMember("doc", doc);
    Value result = context.eval("python", userScript);
}
```

---

# Safety: What Could Go Wrong?

### Infinite loops

<div class="columns">
<div class="col">

**Static analysis** (pre-execution)
```python
while True:   # ✗ Caught by
  pass        #   semantic analyzer
```

</div>
<div class="col">

**Timeout** (runtime backstop)
```python
while 1 == 1: # ✗ Killed after
  pass        #   20 seconds
```

</div>
</div>

### Context isolation
```python
# Execution 1:  i = 10     → "i equals 10"
# Execution 2:  i = 20     → "i equals 20"
# Execution 3:  print(i)   → NameError: 'i' is not defined  ✓
```

No global state leaks between script executions.

---

# Supported Script Contexts

| Context | Variables | Use Case |
|---------|-----------|----------|
| **Field** | `doc`, `params` | Computed fields at query time |
| **Score** | `doc`, `params`, `_score` | Custom ranking and relevance tuning |
| **Ingest** | `ctx`, `params` | Document transformation during indexing |
| **Search** | `ctx`, `params` | Dynamic search request modification |
| **Template** | `params` | Script testing via execute API |

<br>

Each context exposes exactly the data the script needs — nothing more.

---

# Challenges

### Performance
- GraalVM context creation adds overhead per execution
- Engine **warmup** at startup mitigates cold start
- Python will be **slower than Painless** for simple operations
- Best suited for tasks where **capability > raw speed**

### Security
- Currently uses `TRUSTED` sandbox (needed for native extensions like NumPy)
- No per-script memory limits yet
- Appropriate for **controlled environments** today

### Library Support
- Python stdlib: fully available
- NumPy: bundled, working
- Other packages: requires plugin rebuild to add

---

# Performance: Pure Computation


| Scenario | Painless | Python | Ratio |
|----------|----------|--------|-------|
| Arithmetic (sum 1..100) | 4.0 ms | 8.5 ms | 2.1x |
| String reverse | 3.9 ms | 5.6 ms | 1.4x |
| Fibonacci(20) | 3.5 ms | 6.0 ms | 1.7x |
| Array sort + sum (50 elem) | 2.8 ms | 5.2 ms | 1.9x |

- Averaged on 10 executions, without caching; no document access
- Best suited for tasks where **capability > raw speed**

---

# Roadmap

- **More script contexts**: aggregation, similarity, and more
- **Sandboxing options**: configurable security policies per use case
- **Easier library management**: add packages without rebuilding
- **Performance optimization**: context pooling, script caching
- **Community feedback**: what contexts and features matter most to you?

---

# Try It

**GitHub**: github.com/yuancu/lang-python

```bash
# Build
./gradlew build

# Install
opensearch-plugin install file:///path/to/lang-python-3.4.0.0.zip

# Run your first Python script
POST /_scripts/python/_execute
{ "script": { "source": "'Hello, OpenSearchCon!'" } }
```

<br>

Issues, PRs, and feedback welcome.

---

<!-- _paginate: false -->

# Thank You

<br>

**Yuanchun Shen & Shuang Li**

GitHub: github.com/yuancu/lang-python
OpenSearch Issue: #17432

<br>
<br>

Questions?
