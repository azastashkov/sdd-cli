CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
;
CREATE TABLE repo(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  path TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'UNKNOWN',
  head_commit TEXT,
  branch TEXT,
  dirty_hash TEXT,
  included_builds TEXT,
  gradle_status TEXT,
  parse_status TEXT,
  error TEXT,
  indexed_at TEXT);
;
CREATE TABLE module(
  id INTEGER PRIMARY KEY,
  repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
  gradle_path TEXT NOT NULL,
  grp TEXT,
  name TEXT,
  version TEXT,
  kind TEXT NOT NULL DEFAULT 'UNKNOWN',
  spring_app_name TEXT,
  context_path TEXT);
;
CREATE TABLE artifact(
  id INTEGER PRIMARY KEY,
  grp TEXT NOT NULL,
  name TEXT NOT NULL,
  module_id INTEGER REFERENCES module(id) ON DELETE CASCADE,
  UNIQUE(grp, name));
;
CREATE TABLE dep_edge(
  id INTEGER PRIMARY KEY,
  from_module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  to_grp TEXT NOT NULL,
  to_name TEXT NOT NULL,
  configuration TEXT,
  declared_version TEXT,
  resolved_version TEXT,
  declared_via TEXT,
  mode TEXT,
  is_internal INTEGER NOT NULL DEFAULT 0,
  to_module_id INTEGER REFERENCES module(id));
;
CREATE INDEX ix_dep_to ON dep_edge(to_module_id) WHERE is_internal = 1;
;
CREATE TABLE java_type(
  id INTEGER PRIMARY KEY,
  module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  fqcn TEXT NOT NULL,
  kind TEXT,
  is_api INTEGER NOT NULL DEFAULT 0,
  file_path TEXT,
  signature_hash TEXT,
  api_confidence TEXT,
  annotations TEXT);
;
CREATE INDEX ix_type_fqcn ON java_type(fqcn);
;
CREATE TABLE api_member(
  id INTEGER PRIMARY KEY,
  type_id INTEGER NOT NULL REFERENCES java_type(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  signature TEXT,
  return_type TEXT,
  synthesized_by TEXT);
;
CREATE TABLE api_usage(
  from_module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  target_fqcn TEXT NOT NULL,
  target_module_id INTEGER REFERENCES module(id),
  ref_kind TEXT);
;
CREATE INDEX ix_usage_target ON api_usage(target_module_id, target_fqcn);
;
CREATE TABLE file_ref(
  repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
  src_file TEXT NOT NULL,
  dst_file TEXT NOT NULL,
  ref_count INTEGER NOT NULL DEFAULT 1);
;
CREATE INDEX ix_file_ref_src ON file_ref(repo_id, src_file);
;
CREATE TABLE rest_endpoint(
  id INTEGER PRIMARY KEY,
  module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  class_fqcn TEXT,
  method_name TEXT,
  http_method TEXT,
  path_template TEXT,
  norm_path TEXT,
  request_type TEXT,
  response_type TEXT,
  profile TEXT);
;
CREATE TABLE rest_client(
  id INTEGER PRIMARY KEY,
  module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  kind TEXT,
  class_fqcn TEXT,
  method_or_site TEXT,
  http_method TEXT,
  uri_template TEXT,
  norm_path TEXT,
  target_hint TEXT,
  resolution TEXT,
  raw_expr TEXT);
;
CREATE TABLE rest_call_edge(
  client_id INTEGER NOT NULL REFERENCES rest_client(id) ON DELETE CASCADE,
  endpoint_id INTEGER NOT NULL REFERENCES rest_endpoint(id) ON DELETE CASCADE,
  confidence TEXT,
  matched_by TEXT);
;
CREATE TABLE kafka_topic(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  resolution TEXT);
;
CREATE TABLE kafka_role(
  module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  topic_id INTEGER NOT NULL REFERENCES kafka_topic(id),
  role TEXT NOT NULL,
  class_fqcn TEXT,
  group_id TEXT,
  payload_type TEXT);
;
CREATE INDEX ix_kafka ON kafka_role(topic_id, role);
;
CREATE TABLE config_property(
  module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  key TEXT NOT NULL,
  value TEXT,
  profile TEXT,
  source_file TEXT);
;
CREATE TABLE repo_card(
  repo_id INTEGER PRIMARY KEY REFERENCES repo(id) ON DELETE CASCADE,
  card_md TEXT,
  card_line TEXT,
  model TEXT,
  input_hash TEXT,
  created_at TEXT);
;
CREATE VIRTUAL TABLE fts_symbol USING fts5(
  identifier,
  fqcn,
  words,
  module_id UNINDEXED,
  tokenize = "unicode61 tokenchars '_$'");
;
CREATE VIEW v_repo_dep_edge AS
  SELECT DISTINCT mf.repo_id AS from_repo_id,
                  mt.repo_id AS to_repo_id,
                  e.mode     AS mode,
                  e.declared_via AS declared_via
  FROM dep_edge e
  JOIN module mf ON mf.id = e.from_module_id
  JOIN module mt ON mt.id = e.to_module_id
  WHERE e.is_internal = 1 AND mf.repo_id <> mt.repo_id;
