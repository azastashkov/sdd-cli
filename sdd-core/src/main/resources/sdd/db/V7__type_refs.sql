-- Which TYPE made a reference, not merely which module.
--
-- The knowledge base has never held a type -> type edge inside a repo at all, and the reason is
-- easy to miss because it is split across two mechanisms. ReferenceExtractor writes an api_usage
-- row only when the target is NOT declared in the same repo; an intra-repo reference becomes a
-- file_ref row instead, at FILE granularity. And api_usage records from_module_id, so even for the
-- cross-repo half the referencing type is discarded. UsageLinker then deletes the rows where
-- consumer and provider modules coincide, on the grounds that a module referencing itself is not
-- an edge -- true at module level, and exactly backwards for a type graph, where the intra-module
-- type -> type edge is the most valuable one there is.
--
-- So this is a new table rather than a column on api_usage. Widening api_usage could not reach the
-- edges in question no matter what it stored, and would have to break UsageLinker's pruning to try.
--
-- from_type_id is a real FK, not a written fqcn, for one concrete reason: SourcePersistence already
-- opens with DELETE FROM java_type WHERE module_id=:m, and Database.dataSource sets
-- enforceForeignKeys(true), so ON DELETE CASCADE gives this table its per-module wipe for free. The
-- estate-wide re-insert pattern in SourceExtraction.extractRepo gains no new statement.
--
-- The consequence is stated rather than discovered: only a type that reaches java_type can be a
-- SOURCE here. Since 2026-08-20 that includes top-level package-private types, which is what makes
-- the table worth having -- before that widening, the listeners and config classes a change-impact
-- question is actually about were invisible at both ends of every edge.
--
-- to_fqcn is text because a target may be external, or in a repo indexed later, exactly as
-- api_usage.target_fqcn and type_supertype.supertype_fqcn are. It is resolved by name-join at query
-- time, the same way KbHierarchy.subtypesOf already resolves a supertype.
--
-- ref_kind is IMPORT / EXTENDS / CALL / TYPE, as ReferenceExtractor already classifies. Unlike
-- api_usage.ref_kind, which has been written by one site and read by none since V1, this column
-- ships with a reader: an inbound EXTENDS edge outranks an inbound CALL at equal graph distance,
-- because the types that must change alongside a changed interface are its implementors. A fact
-- with no reader is worse than no fact.
--
-- ref_count is how many distinct reference sites collapsed into this row, mirroring
-- file_ref.ref_count. It bounds the traversal frontier. It is deliberately NOT part of any ordering
-- the drafter prompt depends on: a plan hash must not move because someone added a call site
-- somewhere in the estate.
CREATE TABLE type_ref(
  from_type_id INTEGER NOT NULL REFERENCES java_type(id) ON DELETE CASCADE,
  to_fqcn      TEXT NOT NULL,
  ref_kind     TEXT NOT NULL,
  ref_count    INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY(from_type_id, to_fqcn, ref_kind))
;
-- The inbound direction -- "who references X" -- is the whole point, and it is the one the
-- composite primary key's implicit index cannot serve, being prefixed on from_type_id. There is
-- deliberately no index on from_type_id alone: it would duplicate the primary key's own.
CREATE INDEX ix_type_ref_to ON type_ref(to_fqcn)
