package org.webjars;

import java.util.SortedMap;

public interface WebJarAssetResolver {
    String resolve(SortedMap<String, String> fullPaths, String partialPath) throws RuntimeException;
}
