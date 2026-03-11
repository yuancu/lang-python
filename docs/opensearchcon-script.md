# Speaker Script — Python on OpenSearch

> Yuanchun narrates all slides. Shuang operates the terminal for live demos.
> Target: ~15 minutes of content + 5 minutes Q&A.
> Approximate timing is marked for each slide.

---

## Slide 1: Title (0:00 – 0:30)

Hi everyone. I'm Yuanchun Shen, and this is my colleague Shuang Li. We're here today to talk about something we've been exploring — running Python natively inside OpenSearch.

We'll show you what it looks like, how it works under the hood, and where we think it can go from here.

---

## Slide 2: Why Python? (0:30 – 1:30)

So, if you've written custom scripts in OpenSearch, you've probably used Painless. Painless is the default scripting language. It's designed to be safe, and it's fast.

But here's the thing — most of us don't write Painless day to day. We write Python. Our data pipelines are in Python. Our ML models are in Python. Our quick-and-dirty string processing? Also Python.

So we asked ourselves: what if we could just use Python directly inside OpenSearch? Skip the context switch, skip learning a new language, and get access to the Python ecosystem at the same time.

---

## Slide 3: Painless vs Python — Simple Case (1:30 – 2:30)

Let's start with a comparison. Here's a common task — concatenating a first name and a last name from document fields.

On the left, Painless. On the right, Python. They look pretty similar here, honestly. For simple tasks like this, Painless works fine.

But notice — in Python, this is just how you'd normally write it. No special syntax to learn. And the real difference shows up when things get more complex.

---

## Slide 4: When Painless Isn't Enough (2:30 – 3:30)

Here's a slightly more interesting example. We want to compute an average rating from an array of numbers and multiply it by a parameter.

In Painless, you need a manual for-loop — declare a variable, iterate, accumulate, divide. It's about seven lines.

In Python, it's one line. `sum`, `len`, multiply. Done.

And this is still a fairly simple case. Once you need regex, JSON parsing, HTTP calls, or anything from the Python standard library, the gap gets much wider. That's where this project really shines.

---

## Slide 5: What We Built (3:30 – 4:15)

So what did we actually build? It's a Python Language Plugin for OpenSearch.

It lets you write scripts in Python across four script contexts — ingest, search, scoring, and field scripts. You get the full Python standard library — math, json, re, collections — all built in. And we've also bundled NumPy as a proof of concept for third-party libraries.

The plugin itself is about 1,400 lines of custom Java code. The rest is generated ANTLR grammar for Python parsing. It's a small plugin, but it opens up a lot of possibilities.

Alright, enough talking. Let me show you. Shuang?

---

## Slide 6: Demo 1 — Python Runs Inside OpenSearch (4:15 – 5:00)

> **[Shuang runs the demo on terminal]**

So this is the simplest thing we can do. Shuang is sending a POST request to the execute API with a small Python expression — `import math; math.factorial(10)`.

And we get back 3,628,800. That's Python running inside OpenSearch, in the JVM — not a subprocess, not a sidecar container. The standard library is just there.

You can use `json.dumps`, `re.match`, `collections.Counter` — whatever you need. No extra setup.

---

## Slide 7: Demo 2 — Custom Scoring with Python + NumPy (5:00 – 7:00)

> **[Shuang sets up the books index with 5 books and runs Part 1]**

Now let's do something more useful. We have a books index — each book has a title and an array of reader ratings.

First, a simple approach — we store a Python script that computes the average rating and multiplies it by a parameter. One line of Python: `sum`, `len`, multiply. Shuang is running a `function_score` query with this script.

The Great Gatsby gets 10, Dune gets 8.57, 1984 gets 8. Gatsby wins because it has all fives. Simple and intuitive.

> **[Shuang runs Part 2 — NumPy scoring]**

But what if we want a smarter ranking? Maybe a book with seven ratings should rank higher than one with three, even if the average is slightly lower. We want to reward consistency and confidence.

So here's a NumPy-powered scoring script. It uses `np.mean`, `np.std`, and `np.log2` to compute a confidence-weighted score: higher mean is better, lower standard deviation gives a bonus, and more ratings increase confidence on a log scale.

---

## Slide 8: Demo 2 — Results (7:00 – 7:45)

And look at the difference — Dune jumps to number one. It has seven ratings with good consistency, so the confidence-weighted formula rewards it over Gatsby's perfect but small sample of three ratings. Fahrenheit 451 — all threes — also moves up because perfect consistency counts.

This is the kind of ranking logic that would take dozens of lines in Painless — manual loops for mean and standard deviation, no built-in log function. With NumPy, it's readable and concise. And this is just one example — you could do cosine similarity, percentile calculations, outlier detection — things that are natural in NumPy but impractical in Painless.

---

## Slide 9: Demo 3 — AI-Powered Script Fields with OpenAI (7:45 – 9:15)

> **[Shuang runs the OpenAI demo on terminal]**

Now here's where it gets really interesting. We have an index of trivia questions — just simple factual questions stored as documents.

Shuang is running a search query with a Python script field. For each document, the script reads the question, calls OpenAI's chat completion API directly using `urllib.request`, parses the JSON response, and returns the answer — all at query time.

The API key is passed via `params`, so it's never stored in OpenSearch. The script is pure Python standard library — `json` and `urllib.request`. No special SDK, no extra dependencies.

> **[Shuang shows the results with AI answers]**

And there we go — each question now has an AI-generated answer as a script field. "What is the capital of France?" — "Paris." This pattern works for any external API — summarization, classification, translation, embeddings. In Painless, you simply can't make an HTTP call. In Python, it's natural.

---

## Slide 10: How It Works (9:15 – 10:15)

OK so you've seen what it does — let me explain how it works.

When a Python script comes in, it goes through three stages. First, we parse it with an ANTLR4 Python grammar — this catches syntax errors early. Then our semantic analyzer runs — this is a safety check, mainly looking for infinite loops. And finally, the script runs inside a GraalVM polyglot context.

The key point is: Python runs inside the JVM. There's no external process, no network call to a Python runtime, no serialization overhead for passing data back and forth. Java objects like `doc` and `params` are passed directly into the Python context.

---

## Slide 11: GraalVM — The Key Enabler (10:15 – 11:00)

GraalVM is what makes this possible. Its polyglot API lets you embed multiple language runtimes in the same JVM process.

We use GraalPy — GraalVM's Python implementation — to evaluate user scripts. We put Java objects like `doc` and `params` into the Python bindings, evaluate the script, and extract the result. And every execution gets a fresh context — there's no shared state between calls.

This code snippet here is a simplified version of what the plugin actually does. It's about five lines to set up and run a Python script. GraalVM handles the rest.

---

## Slide 12: Safety (11:00 – 12:00)

Now, running user-provided code inside your search engine — that raises some obvious questions. What if someone writes an infinite loop?

We handle this at two levels. First, before execution, our semantic analyzer parses the code and catches obvious patterns like `while True` with no break statement. That's a static check — it's instant.

But you can't catch everything statically. `while 1 == 1` is semantically the same as `while True`, but harder to detect at parse time. For cases like that, we have a hard timeout — if a script runs longer than 20 seconds, it gets killed.

We also tested context isolation carefully. If one script sets a variable `i` to 10, the next script cannot see it. Each execution is completely independent. No information leaks between scripts.

---

## Slide 13: Supported Script Contexts (12:00 – 12:30)

Here's a quick overview of the script contexts we support. Field scripts for computed fields at query time. Score scripts for custom ranking. Ingest scripts for document transformation during indexing. Search scripts for modifying search requests dynamically — think A/B testing or conditional query rewriting. And template scripts for testing via the execute API, which is what we used in the first demo.

Each context gives the script access to exactly the variables it needs.

---

## Slide 14: Challenges (12:30 – 13:15)

Let me be honest about the challenges.

Performance — creating a GraalVM context has overhead. Python will be slower than Painless for simple operations. We mitigate cold start with engine warmup, but this plugin is best suited for tasks where capability matters more than raw speed. If you're doing a simple field lookup, use Painless. If you need to call Bedrock or run NumPy — that's where Python earns its overhead.

Security — we currently use a trusted sandbox policy. That's needed because libraries like NumPy use native C extensions. This means the plugin is appropriate for controlled environments today, not for running untrusted user code.

Library support — the full standard library works. NumPy is bundled. But adding other packages currently requires rebuilding the plugin.

---

## Slide 15: Performance Numbers (13:15 – 13:45)

Here are some benchmark numbers. We measured pure computation — no document I/O — using the execute API with a warmed context pool.

Python is about 1.4 to 2.1x slower than Painless for basic operations like arithmetic, string manipulation, and Fibonacci. That's a very reasonable overhead.

The takeaway: for simple operations, Painless is faster — that's expected. But the gap is not as dramatic as you might think, and for anything involving library calls, complex math, or external integrations, Python gives you capabilities that Painless simply doesn't have.

---

## Slide 16: Roadmap (13:45 – 14:15)

Looking ahead — we want to add more script contexts, like aggregation and similarity. We want to offer configurable sandboxing so users can choose their security tradeoff. We want to make it easier to add Python packages without rebuilding. And we want to improve performance with context pooling.

But most importantly, we want to hear from you. Which contexts matter most? What would you use this for? That feedback will shape where this goes next.

---

## Slide 17: Try It (14:15 – 14:45)

The plugin is open source on GitHub. You can build it with Gradle, install it like any OpenSearch plugin, and run your first Python script in about two minutes.

We'd love for you to try it out, file issues, tell us what works and what doesn't. The link is on the slide, and we'll leave it up during Q&A.

---

## Slide 18: Thank You (14:45 – 15:00)

That's it from us. Thank you for listening. We're happy to take questions.

> **[Q&A: 5 minutes. Yuanchun takes architecture/design questions, Shuang handles demo/usage questions.]**
