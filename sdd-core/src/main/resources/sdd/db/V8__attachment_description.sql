-- Model-written descriptions of Confluence images, cached so a re-run does not re-describe.
--
-- Describing one image costs a download, an upload, TWO chat calls and a delete. A single real page
-- in this estate carried 26 attachments. Without a cache, re-running `sdd plan` against a page
-- somebody is iterating on pays that again every time — and worse, rewrites descriptions a human
-- may already have reviewed, since a vision model is not reproducible.
--
-- The key is a hash of everything that could change the answer, and the system prompt is in it for
-- the reason repo_card's own comment records: leave the prompt out and a prompt-only improvement
-- becomes a permanent no-op for every image already cached. The model name is in it because
-- switching vision models must invalidate. attachment_version is in it because a diagram
-- re-uploaded under the same filename is a DIFFERENT image, and a cache keyed on the name alone
-- would serve last month's description of it indefinitely, inside a document about to be treated as
-- a requirement.
--
-- No foreign key and no ON DELETE CASCADE, unlike every other table here: a Confluence page is not
-- a row in this database and never will be. That also means nothing prunes this table. It is small
-- (one short row per image ever described) and a stale row is unreachable rather than wrong, since
-- a changed input simply hashes elsewhere.
--
-- disagreement is stored ALONGSIDE the description rather than folded into it, so that a later
-- change to how the flag is rendered does not have to invalidate every cached description to take
-- effect. Empty string means the two readings agreed; NULL never occurs.
CREATE TABLE attachment_description(
  input_hash TEXT PRIMARY KEY,
  page_id TEXT NOT NULL,
  filename TEXT NOT NULL,
  attachment_version TEXT,
  model TEXT,
  description TEXT NOT NULL,
  disagreement TEXT NOT NULL,
  created_at TEXT)
;
CREATE INDEX idx_attachment_description_page ON attachment_description(page_id)
