package de.sourcescout.search;

import de.sourcescout.core.SearchHit;

import java.util.List;

public interface SearchProvider {

    String id();

    default boolean isReady() {
        return true;
    }

    List<SearchHit> search(String topic);
}
