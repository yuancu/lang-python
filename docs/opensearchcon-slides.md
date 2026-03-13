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

<!--
Hi everyone. I'm Yuanchun Shen, and this is my colleague Shuang Li. We're here today to talk about something we've been exploring — running Python natively inside OpenSearch.

We'll show you what it looks like, how it works under the hood, and where we think it can go from here.
-->

---

# About Us

- **Shen Yuanchun** & **Li Shuang** — Machine Learning Engineers, Shanghai OpenSearch Team
- Our team builds both software and machine learning solutions on OpenSearch
- Contributors: Yuanchun, Shuang, Charlie, and many from the Shanghai OpenSearch team

<!--
We're both machine learning engineers on the Shanghai OpenSearch team. Our team builds both software and machine learning solutions on OpenSearch. This project is a collaboration between us, Charlie, and many others from the team.
-->

---

# Why Python?

OpenSearch scripting today means **Painless** — a purpose-built language.

But most developers already know Python.

<br>

> What if you could write OpenSearch scripts in the language you already use every day?

<!--
So, if you've written custom scripts in OpenSearch, you've probably used Painless. Painless is the default scripting language. It's designed to be safe, and it's fast.

But here's the thing — most of us don't write Painless day to day. We write Python. Our data pipelines are in Python. Our ML models are in Python. Our quick-and-dirty string processing? Also Python.

So we asked ourselves: what if we could just use Python directly inside OpenSearch? Skip the context switch, skip learning a new language, and get access to the Python ecosystem at the same time.
-->

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
doc['first_name.keyword'].value
  + ' '
  + doc['last_name.keyword'].value
```

</div>
</div>

<br>

Similar here. But what about **libraries**, **string methods**, **list comprehensions**?

<!--
Let's start with a comparison. Here's a common task — concatenating a first name and a last name from document fields.

On the left, Painless. On the right, Python. They look pretty similar here, honestly. For simple tasks like this, Painless works fine.

But notice — in Python, this is just how you'd normally write it. No special syntax to learn. And the real difference shows up when things get more complex.
-->

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

<!--
Here's a slightly more interesting example. We want to compute an average rating from an array of numbers and multiply it by a parameter.

In Painless, you need a manual for-loop — declare a variable, iterate, accumulate, divide. It's about seven lines.

In Python, it's one line. sum, len, multiply. Done.

And this is still a fairly simple case. Once you need regex, JSON parsing, HTTP calls, or anything from the Python standard library, the gap gets much wider. That's where this project really shines.
-->

---

# What We Built

A **Python Language Plugin** for OpenSearch

- Script contexts (where and how a script runs) supported so far: **ingest**, **search**, **scoring**, **field**
- Use Python standard library: `math`, `json`, `re`, `collections`, ...
- Import third-party libraries like **NumPy**
- ~1,400 lines of custom code (the rest is generated grammar)

<br>

Let me show you.

<!--
So what did we actually build? It's a Python Language Plugin for OpenSearch.

In OpenSearch, a script context defines where and how a script runs — it determines what data the script can access and what it's expected to return. So far we've built support for ingest, search, scoring, and field script contexts, with more on the way. You get the full Python standard library — math, json, re, collections — all built in. And we've also bundled NumPy as a proof of concept for third-party libraries.

The plugin itself is about 1,400 lines of custom Java code. It's a small plugin, but it opens up a lot of possibilities.

Alright, enough talking. Let my colleague Li Shuang show you how it works in action.
-->

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

<!--
[Shuang runs the demo on terminal]

So this is the simplest thing we can do. Shuang is sending a POST request to the execute API with a small Python expression — import math; math.factorial(10).

And we get back 3,628,800. That's Python running inside OpenSearch, in the JVM — not a subprocess, not a sidecar container. The standard library is just there.

You can use json.dumps, re.match, collections.Counter — whatever you need. No extra setup.
-->

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

<!--
[Shuang sets up the books index with 5 books and runs Part 1]

Now let's do something more useful. We have a books index — each book has a title and an array of reader ratings.

First, a simple approach — we store a Python script that computes the average rating and multiplies it by a parameter. One line of Python: sum, len, multiply. Shuang is running a function_score query with this script.

The Great Gatsby gets 10, Dune gets 8.57, 1984 gets 8. Gatsby wins because it has all fives. Simple and intuitive.

[Shuang runs Part 2 — NumPy scoring]

But what if we want a smarter ranking? Maybe a book with seven ratings should rank higher than one with three, even if the average is slightly lower. We want to reward consistency and confidence.

So here's a NumPy-powered scoring script. It uses np.mean, np.std, and np.log2 to compute a confidence-weighted score: higher mean is better, lower standard deviation gives a bonus, and more ratings increase confidence on a log scale.
-->

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

<!--
And look at the difference — Dune jumps to number one. It has seven ratings with good consistency, so the confidence-weighted formula rewards it over Gatsby's perfect but small sample of three ratings. Fahrenheit 451 — all threes — also moves up because perfect consistency counts.

This is the kind of ranking logic that would take dozens of lines in Painless — manual loops for mean and standard deviation, no built-in log function. With NumPy, it's readable and concise. And this is just one example — you could do cosine similarity, percentile calculations, outlier detection — things that are natural in NumPy but impractical in Painless.
-->

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

<!--
[Shuang runs the OpenAI demo on terminal]

Now here's where it gets really interesting. We have an index of trivia questions — just simple factual questions stored as documents.

Shuang is running a search query with a Python script field. For each document, the script reads the question, calls OpenAI's chat completion API directly using urllib.request, parses the JSON response, and returns the answer — all at query time.

The API key is passed via params, so it's never stored in OpenSearch. The script is pure Python standard library — json and urllib.request. No special SDK, no extra dependencies.

[Shuang shows the results with AI answers]

And there we go — each question now has an AI-generated answer as a script field. "What is the capital of France?" — "Paris." This pattern works for any external API — summarization, classification, translation, embeddings. In Painless, you simply can't make an HTTP call. In Python, it's natural.
-->

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

<!--
OK so you've seen what it does — let me explain how it works.

When a Python script comes in, it goes through three stages. First, we parse it with Python AST written in ANTLR 4 — this catches syntax errors early. Then our semantic analyzer runs — this is a safety check, mainly looking for infinite loops. And finally, the script runs inside a GraalVM polyglot context.

The key point is: Python runs inside the JVM. There's no external process, no network call to a Python runtime, no serialization overhead for passing data back and forth. Your document fields and query parameters are passed directly from Java into the Python runtime — no copying, no conversion.
-->

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

<!--
GraalVM is what makes this possible. Its polyglot API lets you embed multiple language runtimes in the same JVM process.

We use GraalPy to evaluate user scripts. GraalPy is GraalVM's Python implementation. We pass in the document fields and query parameters so the Python script can access them directly, then extract the result. And every execution gets a fresh context — there's no shared state between calls.

This code snippet here is a simplified version of what the plugin actually does. It's about five lines to set up and run a Python script. GraalVM handles the rest.
-->

---

# Safety: What Could Go Wrong?

| Threat | Mitigation |
|--------|------------|
| **Infinite loops** | Static analyzer catches `while True`; 20s timeout kills the rest |
| **Resource abuse** | Fresh GraalVM context per call, disposed after; statement limits available |
| **Data leakage** | Context isolation — scripts only see bindings passed in, no cross-execution state |

- Sandbox: **trusted** (for NumPy native extensions); stricter policies can further restrict host I/O and native access

<!--
Now, running user-provided code inside your search engine — that raises some obvious questions. What if someone writes an infinite loop? What if a script consumes too many resources? What if it tries to steal your credentials?

For infinite loops, we have two layers. A static analyzer catches obvious patterns like while True with no break before the script even runs. For trickier cases that slip past static analysis, there's a hard timeout — if a script doesn't finish, it gets killed.

For resource consumption, each script gets a fresh GraalVM context that's disposed right after — nothing accumulates. GraalVM also supports per-context limits like statement caps and memory-constrained sandboxing, which we plan to enable as the plugin matures.

For isolation, each script only sees the bindings explicitly passed in — like document fields and query parameters — and there's no shared state between executions. We currently use a trusted sandbox policy because NumPy requires native extensions, but stricter policies can further restrict host I/O and native access.
-->

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

<!--
Here's a quick overview of the script contexts we support. Field scripts for computed fields at query time. Score scripts for custom ranking. Ingest scripts for document transformation during indexing. Search scripts for modifying search requests dynamically — think A/B testing or conditional query rewriting. And template scripts for testing via the execute API, which is what we used in the first demo.

Each context gives the script access to exactly the variables it needs.
-->

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

<!--
Let me be honest about the challenges.

Performance — creating a GraalVM context has overhead. Python will be slower than Painless for simple operations. But this plugin is best suited for tasks where capability matters more than raw speed. If you're doing a simple field lookup, use Painless. If you need to call Bedrock or run NumPy — that's where Python earns its overhead.

Security — we currently use a trusted sandbox policy. That's needed because libraries like NumPy use native C extensions. This means the plugin is appropriate for controlled environments today, not for running untrusted user code.

Library support — the full standard library works. NumPy is bundled. But adding other packages currently requires rebuilding the plugin.
-->

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

<!--
Here are some benchmark numbers. We measured pure computation — no document I/O — using the execute API with a warmed context pool.

Python is about 1.4 to 2.1x slower than Painless for basic operations like arithmetic, string manipulation, and Fibonacci. That's a very reasonable overhead.

The takeaway: for simple operations, Painless is faster — that's expected. But the gap is not as dramatic as you might think, and for anything involving library calls, complex math, or external integrations, Python gives you capabilities that Painless simply doesn't have.
-->

---

# Roadmap

- **More script contexts**: aggregation, similarity, and more
- **Sandboxing options**: configurable security policies per use case
- **Easier library management**: add packages without rebuilding
- **Performance optimization**: pooling, script caching
- **Community feedback**: what features matter most to you?

<!--
Looking ahead — we want to add more script contexts, like aggregation and similarity. We want to offer configurable sandboxing so users can choose their security tradeoff. We want to make it easier to add Python packages without rebuilding. And we want to further improve performance.

But most importantly, we want to hear from you. What would you use this for? That feedback will shape where this goes next.
-->

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

<!--
The plugin is open source on GitHub. You can build it with Gradle, install it like any OpenSearch plugin, and run your first Python script in about two minutes.

We'd love for you to try it out, file issues, tell us what works and what doesn't. The link is on the slide, and we'll leave it up during Q&A.
-->

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

<!--
That's it from us. Thank you for listening. We're happy to take questions.

[Q&A: 5 minutes. Yuanchun takes architecture/design questions, Shuang handles demo/usage questions.]
-->
