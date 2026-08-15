DROP TABLE fts_symbol;
;
CREATE VIRTUAL TABLE fts_symbol USING fts5(
  identifier,
  fqcn,
  words,
  doc,
  module_id UNINDEXED,
  tokenize = "porter unicode61 tokenchars '_$'");
