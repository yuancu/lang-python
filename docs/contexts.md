# Contexts supported by lang-python

## Field

Field scripts calculate custom field values for each document in search results.

**Use cases:**

- Combining multiple fields
- Formatting data
- Calculated fields

**Variables**

- `params: dict[str, Any]`: User-provided parameters from the query. The document source is also available via
  `params['_source']`.
- `doc: dict[str, Any]`: Document fields. Access values using `doc['field_name'][index]` or
  `doc['field_name'].getValue()`.

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
        "source": "doc['first_name.keyword'].getValue() + ' ' + doc['last_name.keyword'].getValue()"
      }
    }
  }
}
```

Response (simplified):

```json
{
  "hits": {
    "hits": [
      {
        "_id": "1",
        "fields": {
          "full_name": [
            "Jane Doe"
          ]
        }
      },
      {
        "_id": "2",
        "fields": {
          "full_name": [
            "John Smith"
          ]
        }
      }
    ]
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
- `doc: dict[str, Any]`: Fields from the document, which can be accessed with `doc['field_name'].getValue()` or
  `doc['field_name'][index]`.
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

Response (simplified):

```json
{
  "hits": {
    "max_score": 10,
    "hits": [
      {
        "_id": "2",
        "_score": 10,
        "_source": {
          "ratings": [
            5,
            5,
            5
          ]
        }
      },
      {
        "_id": "1",
        "_score": 8,
        "_source": {
          "ratings": [
            4,
            3,
            5
          ]
        }
      },
      {
        "_id": "3",
        "_score": 5.3333335,
        "_source": {
          "ratings": [
            2,
            1,
            5
          ]
        }
      }
    ]
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

Response:

```json
{
  "docs": [
    {
      "doc": {
        "_index": "_index",
        "_id": "_id",
        "_source": {
          "env": "us-east-1-prod",
          "tags": "prod"
        },
        "_ingest": {
          "timestamp": "2025-11-25T06:22:29.359771Z"
        }
      }
    }
  ]
}
```

## Search processor

Search processor scripts modify search requests before they are executed.

**Use cases:**

- Enabling/disabling query features (e.g., explain)
- Implementing custom search request transformations

**Variables**

- `params: dict[str, Any]`: User-provided parameters from the processor configuration.
- `ctx['_source']: dict[str, Any]`: A map representing the search request that can be modified. You can write to properties like `size`, `from`, `explain`, `timeout`, etc.

**Example - Conditional logic with variables:**

```json
PUT /_search/pipeline/conditional_limit
{
  "request_processors": [
    {
      "script": {
        "lang": "python",
        "source": "MAX_SIZE = 5\nDEFAULT_SIZE = 3\nctx['_source']['size'] = MAX_SIZE if True else DEFAULT_SIZE"
      }
    }
  ]
}
```

```json
GET /test_index/_search?search_pipeline=conditional_limit
{
  "query": {
    "match_all": {}
  },
  "size": 20
}
```

Response (simplified):

```json
{
  "hits": {
    "hits": [
      {
        "_id": "1",
        "_source": {
          "title": "Document 1"
        }
      },
      {
        "_id": "2",
        "_source": {
          "title": "Document 2"
        }
      },
      {
        "_id": "3",
        "_source": {
          "title": "Document 3"
        }
      },
      {
        "_id": "4",
        "_source": {
          "title": "Document 4"
        }
      },
      {
        "_id": "5",
        "_source": {
          "title": "Document 5"
        }
      }
    ]
  }
}
```
