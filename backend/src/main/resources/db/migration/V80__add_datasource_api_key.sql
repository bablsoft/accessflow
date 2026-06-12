-- API key for the search engines (Elasticsearch / OpenSearch). AES-256-GCM encrypted before
-- persistence (like password_encrypted), @JsonIgnore on the entity, never returned in an API
-- response. Nullable for every dialect (basic-auth search datasources and all other engines leave
-- it null), so this is a zero-downtime additive change (issue #420).
ALTER TABLE datasources ADD COLUMN api_key_encrypted TEXT;
