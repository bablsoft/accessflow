import type { DbType } from '@/types/api';

/**
 * Elasticsearch and OpenSearch share one connection shape: authentication is either basic creds
 * or an API key, and the API-key mode stores a blank username. Shared by the create wizard and
 * the datasource settings page so neither can require a username the other never collects.
 */
export const SEARCH_ENGINES: DbType[] = ['ELASTICSEARCH', 'OPENSEARCH'];
