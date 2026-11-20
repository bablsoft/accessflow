import type {
  CreateDatasourceInput,
  DatasourceTypeOption,
  SslMode,
  UpdateDatasourceInput,
} from '@/types/api';
import { engineFlags } from './engineFlags';

export type AuthMethod = 'basic' | 'api_key';

export interface ConnectionFormValues {
  name: string;
  host: string;
  port: number;
  database_name: string;
  jdbc_url: string;
  dynamodb_endpoint: string;
  neo4j_bolt_uri: string;
  snowflake_url_override: string;
  bigquery_endpoint: string;
  databricks_http_path: string;
  local_datacenter: string;
  auth_method: AuthMethod;
  api_key: string;
  username: string;
  password: string;
  private_key_passphrase: string;
  ssl_mode: SslMode;
}

function credentials(type: DatasourceTypeOption, values: ConnectionFormValues) {
  const { isSearchEngine, isBigQuery, isDatabricks } = engineFlags(type);
  const useApiKey = isSearchEngine && values.auth_method === 'api_key';
  // API-key auth replaces basic creds; send blank username/password (the backend keeps the
  // NOT NULL columns satisfied) and the encrypted-at-rest API key. BigQuery (service-account
  // JSON) and Databricks (PAT) have no username either.
  return {
    useApiKey,
    username: useApiKey || isBigQuery || isDatabricks ? '' : values.username,
    password: useApiKey ? '' : values.password,
  };
}

/** The body of the first save, which creates the datasource. */
export function toCreateInput(
  type: DatasourceTypeOption,
  values: ConnectionFormValues,
): CreateDatasourceInput {
  const { isDynamic, isCqlEngine, isDynamoDb, isNeo4j, isSnowflake, isBigQuery, isDatabricks } =
    engineFlags(type);
  const { useApiKey, username, password } = credentials(type, values);
  const input: CreateDatasourceInput = {
    name: values.name,
    db_type: type.code,
    username,
    password,
    ssl_mode: values.ssl_mode,
    ai_analysis_enabled: false,
    ai_config_id: null,
    custom_driver_id: type.custom_driver_id ?? null,
    connector_id: type.connector_id ?? null,
  };
  if (isDynamic) {
    input.jdbc_url_override = values.jdbc_url;
  } else if (isDynamoDb) {
    // Cloud-credentials model: database_name is the AWS region; the optional custom endpoint
    // (DynamoDB Local / VPC) rides on jdbc_url_override. Host/port are unused.
    input.database_name = values.database_name;
    if (values.dynamodb_endpoint) {
      input.jdbc_url_override = values.dynamodb_endpoint;
    }
  } else if (isBigQuery) {
    // Cloud-credentials model: database_name is the GCP project (optionally project.dataset)
    // and the optional emulator endpoint rides on jdbc_url_override. Host/port are unused.
    input.database_name = values.database_name;
    if (values.bigquery_endpoint) {
      input.jdbc_url_override = values.bigquery_endpoint;
    }
  } else if (isSnowflake || isDatabricks) {
    // Warehouse hosts are HTTPS endpoints — port is always 443 and never sent. Snowflake's
    // override (warehouse/role/schema params) is optional; Databricks' warehouse HTTP path
    // is required.
    input.host = values.host;
    input.database_name = values.database_name;
    if (isDatabricks) {
      input.jdbc_url_override = values.databricks_http_path;
    } else if (values.snowflake_url_override) {
      input.jdbc_url_override = values.snowflake_url_override;
    }
  } else {
    input.host = values.host;
    input.port = values.port;
    input.database_name = values.database_name;
  }
  if (isCqlEngine) {
    input.local_datacenter = values.local_datacenter;
  }
  if (isNeo4j && values.neo4j_bolt_uri) {
    // Optional full bolt:// / neo4j+s:// URI (Aura / clustered routing) overrides host/port.
    input.jdbc_url_override = values.neo4j_bolt_uri;
  }
  if (useApiKey) {
    input.api_key = values.api_key;
  }
  if (isSnowflake && values.private_key_passphrase) {
    // Only for a passphrase-protected PKCS#8 key; a password or plain PEM leaves it unset.
    input.private_key_passphrase = values.private_key_passphrase;
  }
  return input;
}

/** The body of a later save from the connection step, which updates the created datasource. */
export function toUpdateInput(
  type: DatasourceTypeOption,
  values: ConnectionFormValues,
): UpdateDatasourceInput {
  const {
    isDynamic,
    isCqlEngine,
    isDynamoDb,
    isNeo4j,
    isSnowflake,
    isBigQuery,
    isDatabricks,
    isSearchEngine,
  } = engineFlags(type);
  const { useApiKey, username, password } = credentials(type, values);
  const input: UpdateDatasourceInput = {
    name: values.name,
    username,
    password,
    ssl_mode: values.ssl_mode,
  };
  if (isDynamic) {
    input.jdbc_url_override = values.jdbc_url;
  } else if (isDynamoDb) {
    // Cloud-credentials model: database_name is the AWS region; the optional custom endpoint
    // (DynamoDB Local / VPC) rides on jdbc_url_override. Host/port are unused.
    input.database_name = values.database_name;
    input.jdbc_url_override = values.dynamodb_endpoint || '';
  } else if (isBigQuery) {
    // Cloud-credentials model: database_name is the GCP project (optionally project.dataset)
    // and the optional emulator endpoint rides on jdbc_url_override. Host/port are unused.
    input.database_name = values.database_name;
    input.jdbc_url_override = values.bigquery_endpoint || '';
  } else if (isSnowflake || isDatabricks) {
    // Warehouse hosts are HTTPS endpoints — port is always 443 and never sent.
    input.host = values.host;
    input.database_name = values.database_name;
    input.jdbc_url_override = isSnowflake
      ? values.snowflake_url_override || ''
      : values.databricks_http_path;
  } else {
    input.host = values.host;
    input.port = values.port;
    input.database_name = values.database_name;
  }
  if (isCqlEngine) {
    input.local_datacenter = values.local_datacenter;
  }
  if (isNeo4j) {
    // Optional full bolt:// / neo4j+s:// URI (Aura / clustered routing) overrides host/port.
    input.jdbc_url_override = values.neo4j_bolt_uri || '';
  }
  if (isSearchEngine) {
    input.api_key = useApiKey ? values.api_key : '';
  }
  if (isSnowflake) {
    // Blank clears a stored passphrase (the key is no longer encrypted). This branch owns a
    // just-created row whose form state is authoritative, so it can use the API's
    // blank-clears semantics; the settings page deliberately cannot — see onFinish there.
    input.private_key_passphrase = values.private_key_passphrase || '';
  }
  return input;
}
