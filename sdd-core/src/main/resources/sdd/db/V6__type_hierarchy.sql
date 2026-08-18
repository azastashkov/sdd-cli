-- The subtype's own identity, which api_usage throws away.
--
-- ReferenceExtractor already resolves every `extends` and `implements` target, but the row it
-- persists is api_usage(from_module_id, target_fqcn, ref_kind='EXTENDS') — so the knowledge base can
-- say "some type in this MODULE extends com.acme.Base" and can never say which one. Measured on the
-- real estate, that is why an interface's implementors could not be named: three of five sat past
-- the drafter's evidence budget with nothing able to promote them, because promotion keys on names
-- and no name existed to supply.
--
-- `implements` and `extends` are recorded distinctly here, unlike in api_usage, which conflates both
-- into 'EXTENDS'.
--
-- resolution records HOW supertype_fqcn was arrived at, on the kafka_topic.resolution principle that
-- unresolved is not nonexistent: 'IMPORT' an import statement named it unambiguously;
-- 'SAME_PACKAGE' no import, so it is package-local by Java's rules; 'WRITTEN' the source spelled a
-- package-qualified name we trust verbatim; 'UNRESOLVED' only the simple name is known. An
-- UNRESOLVED row is still a row -- "no subtypes of X" and "subtypes we could not place" are
-- different answers and must never render the same.
CREATE TABLE type_supertype(
  type_id             INTEGER NOT NULL REFERENCES java_type(id) ON DELETE CASCADE,
  supertype_fqcn      TEXT NOT NULL,
  relation            TEXT NOT NULL,
  resolution          TEXT NOT NULL,
  supertype_module_id INTEGER REFERENCES module(id) ON DELETE SET NULL,
  PRIMARY KEY(type_id, supertype_fqcn, relation))
;
CREATE INDEX ix_supertype_fqcn ON type_supertype(supertype_fqcn)
;
-- Which extractor vintage produced a repo's rows.
--
-- The index short-circuits on head_commit||':'||dirty_hash, which a migration does not change -- so
-- a schema upgrade that changes what the extractors EMIT is invisible to it, and a plain
-- `sdd index` skips every repo and reports success. V4 bought visibility with a bespoke
-- `build_system IS NOT NULL` guard; V2 and V3 bought nothing and left `--force` as the only remedy,
-- which has bitten this project twice. A hand-bumped epoch replaces that idiom: NULL never equals
-- the current value, so V6 self-heals, and a future migration that only widens a reader-side table
-- can deliberately NOT bump it rather than forcing a needless full re-extract.
ALTER TABLE repo ADD COLUMN extractor_epoch INTEGER
