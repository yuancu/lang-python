# Scripting with Python in OpenSearch

## Overview

The Python Language Plugin enables you to write scripts in Python for OpenSearch. Python is simple yet powerful, making
it ideal for data science tasks and reducing the learning curve for developers already familiar with Python.

### Why Python?

- **Familiarity**: Python is one of the most popular programming languages, especially in data science and machine
  learning
- **Simplicity**: Python's clean syntax makes scripts easier to write and maintain
- **Powerful Libraries**: Access to popular Python libraries like NumPy for advanced data processing
- **Versatility**: Use Python for custom scoring, field transformations, and data processing

### What Can You Do?

With Python scripting in OpenSearch, you can:

- **Custom document scoring** - Calculate relevance scores using custom algorithms
- **Field transformations** - Derive values from document fields during queries
- **Ingestion pipelines** - Use python for transformations or embedding during indexing
- **Machine learning integration** - Use libraries like NumPy for advanced computations

## Quick Start

If you are new to scripting in OpenSearch, please refer
to [script query](https://docs.opensearch.org/latest/query-dsl/specialized/script) for general scripting knowledge.

### A taste of Python scripting in OpenSearch

The Python language plugin provides an execute API for testing scripts. Results are returned as a string in the`result`.

```json
POST _scripts/python/_execute
{
  "script": {
    "source": "'hello ' + 'world'"
  }
}
```

Response:

```json
{
  "result": "hello world"
}
```

### Parameters

OpenSearch caches and reuses compiled scripts. To maximize performance, pass variables as parameters rather than
hardcoding them in the script source.

```json
POST _scripts/python/_execute
{
  "script": {
    "source": "f\"Power of {params['i']} is {params['i'] ** 2}\"",
    "params": {
      "i": 10
    }
  }
}
```

Response:

```json
{
  "result": "Power of 10 is 100"
}
```

### Python Libraries

#### Standard libraries

Python's standard library and built-in modules are fully available for use in scripts.

```bash
POST _scripts/python/_execute
{
  "script": {
    "source": "import math; math.sqrt(16)"
  }
}
```

Response:

```json
{
  "result": "4.0"
}
```

#### Third-party libraries

Third-party libraries can be packaged and shipped with the plugin by configuring the `graalPy.packages` section in the
build script. Currently, NumPy and its dependencies are available out of the box. Note that third-party library support
is currently experimental.

```json
POST _scripts/python/_execute
{
  "script": {
    "source": "import numpy as np; np.array([[1, 2], [3, 4]]) @ np.array([5, 6])"
  }
}
```

Response:

```json
{
  "result": "array([17, 39])"
}
```

## Accessing Document Data

### Using `doc` Values

The `doc` map provides access to document field values. This is the **fastest** way to access field data.

**Syntax:**

```python
doc['field_name'].getValue()
```

When an array is stored, use the following syntax to access the i-th element

```python
doc['field_name'][i]
```

**Example:**

```python
# Access a keyword field
doc['category.keyword'][0]

# Access a numeric field
doc['price'].getValue()

# Access an array field
doc['tags'][0]  # First element
doc['tags'][1]  # Second element
```

**Important notes:**

- Only analyzed fields are stored
  as [doc values](https://docs.opensearch.org/latest/mappings/mapping-parameters/doc-values/). This typically includes
  numbers, boolean, date, and analyzed texts.
- Use keyword subfields for text fields: `doc['field.keyword']`. The original text field `doc['field']` is not analyzed
  thus not accessible with doc values.

### Using `params['_source']`

The `params['_source']` map provides access to
the [original document source](https://docs.opensearch.org/latest/mappings/metadata-fields/source/). This is **slower**
than `doc` but provides access to the original document structure, including those that are not accessible with doc
values.

**Syntax:**

```python
params['_source']['field_name']
```

**Example:**

```python
# Access source field
params['_source']['name']

# Access nested field
params['_source']['user']['email']
```

## Script Contexts

Python scripts run within specific contexts in OpenSearch. Each context defines:

- Available variables that can be accessed in your script
- An allowlist of permitted classes, methods, and fields (the API)
- The expected return type (if any)

You can use the following API to check which contexts are supported by the Python language plugin.

```bash
GET /_script_language
```

Some supported contexts are exemplified in [supported contexts](contexts.md).
