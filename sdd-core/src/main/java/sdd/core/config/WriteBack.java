package sdd.core.config;

/**
 * {@code atlassian.write_back} — whether {@code sdd} is allowed to post back to Jira/Confluence
 * (e.g. a progress comment on the driving issue). Unlike {@code retrieval: embeddings} (rejected
 * because no backend for it will ever exist in this build, see {@code SddConfig}'s javadoc),
 * {@code comment} names a real Task 3+/5 capability that simply is not wired up yet in this repo —
 * so {@link ConfigLoader} accepts it here rather than rejecting it as unimplemented; a later task
 * consumes the value, this one only needs to stop it from being silently swallowed or mistyped.
 * Anything other than {@code none}/{@code comment} is a {@link ConfigException} — see the
 * {@code atlassian.write_back must be...} check in {@code ConfigLoader}.
 */
public enum WriteBack { NONE, COMMENT }
