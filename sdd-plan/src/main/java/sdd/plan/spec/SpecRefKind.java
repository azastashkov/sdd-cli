package sdd.plan.spec;

/** What a {@code sdd plan} ref (or {@code --text} companion) looks like, decided by shape
 *  alone — see {@link SpecSources#classify(String)}. */
public enum SpecRefKind { MARKDOWN, CONFLUENCE_EXPORT, JIRA, CONFLUENCE_PAGE }
