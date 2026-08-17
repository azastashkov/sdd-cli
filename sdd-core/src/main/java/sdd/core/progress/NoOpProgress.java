package sdd.core.progress;

/** {@link Progress#noOp()}'s implementation — an enum singleton (Java 21 idiom for "exactly one
 *  instance, no state") whose every method does nothing, so it trivially satisfies "must never
 *  throw" without needing a catch-all of its own. Package-private: reached only through {@link
 *  Progress#noOp()}. */
enum NoOpProgress implements Progress {
    INSTANCE;

    @Override
    public void phase(String name, int total) {
    }

    @Override
    public void start(String item) {
    }

    @Override
    public void finish(String item) {
    }

    @Override
    public void detail(String text) {
    }

    @Override
    public void note(String text) {
    }

    @Override
    public void stop() {
    }
}
