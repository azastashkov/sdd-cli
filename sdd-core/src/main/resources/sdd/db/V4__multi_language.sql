-- Multi-toolchain estates: a repo may be built by Gradle or by npm, and a module's sources may be
-- Java or TypeScript. Three ADD COLUMNs and one index; fts_symbol is deliberately NOT touched, so
-- Database.FTS_REBUILD_VERSION stays at 2 and no javadoc is lost on upgrade.

-- Which build system produced this repo's module rows: 'GRADLE' | 'NPM'. Nullable on purpose:
-- rows written before this migration have no answer, and IndexService's fingerprint short-circuit
-- treats NULL as "not yet re-extracted" so a plain `sdd index` refills it. Do not add a DEFAULT —
-- defaulting to 'GRADLE' would assert something false about an npm repo that today lands FAILED.
ALTER TABLE repo ADD COLUMN build_system TEXT
;
-- 'JAVA' | 'TYPESCRIPT'. NOT NULL DEFAULT is correct here and required by SQLite for an added
-- NOT NULL column: everything indexed before this migration genuinely is Java.
ALTER TABLE module ADD COLUMN language TEXT NOT NULL DEFAULT 'JAVA'
;
-- java_type carries its own language rather than relying on the module join, because UsageLinker
-- resolves api_usage targets by matching java_type.fqcn ALONE, with no module join — so without
-- this column a TypeScript module whose specifier happened to look like a Java package could
-- capture a Java type's usages.
ALTER TABLE java_type ADD COLUMN language TEXT NOT NULL DEFAULT 'JAVA'
;
CREATE INDEX ix_type_lang_fqcn ON java_type(language, fqcn)
