package io.github.makaseloli.ghastfsd.content;

public interface GhastFsdTaskCarrier {
    boolean ghastfsd$hasSyncedTask();

    void ghastfsd$setSyncedTask(boolean hasTask);

    String ghastfsd$syncedCouplingNext();

    void ghastfsd$setSyncedCouplingNext(String nextUuid);

    String ghastfsd$syncedCouplingPrevious();

    void ghastfsd$setSyncedCouplingPrevious(String previousUuid);
}
