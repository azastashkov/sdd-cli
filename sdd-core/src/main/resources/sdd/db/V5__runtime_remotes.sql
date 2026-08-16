-- Micro-frontend style runtime composition: a host application loads other repos' bundles at run
-- time, by URL, from a manifest served to the browser. Nothing about that relationship is visible
-- in any package.json, so without recording it the estate graph shows the host depending on
-- nothing and the loaded modules depended on by nothing.

-- What a checked-in remotes manifest declares. A pure reading of the file: name, URL, whether it
-- is enabled. No inference at all.
CREATE TABLE runtime_remote(
  id INTEGER PRIMARY KEY,
  repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  url TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  source_file TEXT NOT NULL,
  UNIQUE(repo_id, name, source_file))
;
-- Which repo actually builds a declared remote. Deliberately NOT derived from the bundle filename:
-- `/mfe/a/trading-mfe-a.js` resembling the repo `trading-mfe-a` is a naming convention, not a fact,
-- and a graph edge invented from a resemblance is indistinguishable from one that was read. The
-- mapping comes from `runtime_edges` in sdd.yml, so `resolution` records that a human supplied it.
CREATE TABLE runtime_edge(
  host_repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
  module_repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
  remote_name TEXT NOT NULL,
  resolution TEXT NOT NULL,
  PRIMARY KEY(host_repo_id, module_repo_id, remote_name))
