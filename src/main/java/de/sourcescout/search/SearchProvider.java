package de.sourcescout.search;

import de.sourcescout.core.SearchHit;

import java.util.List;

public interface SearchProvider {

    String id();

    List<SearchHit> search(String topic);
}
