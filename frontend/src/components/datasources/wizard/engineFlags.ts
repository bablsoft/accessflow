import { SEARCH_ENGINES } from '@/utils/dbTypeGroups';
import type { DatasourceTypeOption } from '@/types/api';

/** Which connection model a database type uses; drives the fields shown and the payload sent. */
export function engineFlags(type: DatasourceTypeOption) {
  return {
    // CUSTOM via a connector uses host/port/database (URL built from the connector template);
    // only an uploaded/bare CUSTOM driver needs the free-form JDBC URL field.
    isDynamic: type.code === 'CUSTOM' && type.source !== 'connector',
    isCqlEngine: type.code === 'CASSANDRA' || type.code === 'SCYLLADB',
    isDynamoDb: type.code === 'DYNAMODB',
    isNeo4j: type.code === 'NEO4J',
    isSnowflake: type.code === 'SNOWFLAKE',
    isBigQuery: type.code === 'BIGQUERY',
    isDatabricks: type.code === 'DATABRICKS',
    isSearchEngine: SEARCH_ENGINES.includes(type.code),
  };
}
