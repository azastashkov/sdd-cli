package sdd.plan.gen;

import java.util.Objects;

/** One Gate-1 open question; blocking questions need written resolutions before approve (3C-2). */
public record Question(String text, boolean blocking) {
    public Question {
        Objects.requireNonNull(text);
    }
}
