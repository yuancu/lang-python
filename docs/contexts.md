# Contexts supported by lang-python

## Field

Field scripts calculate custom field values for each document in search results.

**Use cases:**

- Combining multiple fields
- Formatting data
- Calculated fields

**Variables**

- `params: dict[str, Any]`: User-provided parameters from the query. The document source is also available via `params['_source']`.
- `doc: dict[str, Any]`: Document fields. Access values using `doc['field_name'][index]` or `doc['field_name'].getValue()`.

**Returns**

- `Any`: The value for the new field.

**Example - Concatenating Fields:**

```json
GET /test_index/_search
{
  "script_fields": {
    "full_name": {
      "script": {
        "lang": "python",
        "source": "doc['first_name.keyword'][0] + ' ' + doc['last_name.keyword'][0]"
      }
    }
  }
}
```

## Score

Score scripts calculate custom relevance scores for documents in search results.

**Use cases:**

- Custom ranking algorithms
- Business logic in scoring
- Multi-factor scoring

**Variables**

- `params: dict[str, Any]`: User-provided parameters from the query.
- `doc: dict[str, Any]`: Fields from the document, which can be accessed with `doc['field_name'].getValue()` or `doc['field_name'][index]`.
- `_score: float`: The relevance score of the document.

**Returns**

- `double`: the computed score of the document

**Example - Weighted Rating Score:**

```json
GET /books/_search
{
  "query": {
    "function_score": {
      "script_score": {
        "script": {
          "lang": "python",
          "source": "sum(doc['ratings']) / len(doc['ratings']) * params['factor']",
          "params": {
            "factor": 2.0
          }
        }
      }
    }
  }
}
```

## Ingest processor

Ingest processor scripts modify documents during data ingestion.

**Use cases:**

- Adding computed fields during data ingestion
- Embedding texts

**Variables**

- `params: dict[str, Any]`: User-provided parameters from the query.
- `ctx: dict[str, Any]`: Ingest context containing document source, `_index` (target index), and `_id` (document ID).
  All fields can be modified to transform the document during ingestion.

**Example - Extract tags from environment name**

```json
POST _ingest/pipeline/_simulate
{
  "pipeline": {
    "processors": [
      {
        "script": {
          "description": "Extract 'tags' from 'env' field",
          "lang": "python",
          "source": "ctx['tags'] = ctx['env'].split(params['delimiter'])[params['position']]",
          "params": {
            "delimiter": "-",
            "position": 3
          }
        }
      }
    ]
  },
  "docs": [
    {
      "_source": {
        "env": "us-east-1-prod"
      }
    }
  ]
}
```
