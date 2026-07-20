-- #612: the auth scheme an uploaded schema document declared, when its format carries one.
-- A Postman collection names its auth type (bearer/basic/apikey/oauth2); the parser reads the type
-- only and never the credential values, which are re-entered by the admin on the connector itself.
-- NULL for every other schema type and for schemas uploaded before this column existed.
ALTER TABLE api_schemas ADD COLUMN detected_auth_method api_auth_method;
