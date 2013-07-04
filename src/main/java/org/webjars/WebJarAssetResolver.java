package org.webjars;

import java.util.SortedMap;

/**
 * Chooses an asset from a pool matching a partial path.
 */
public interface WebJarAssetResolver {

    /**
     * @param fullPaths Assets that match the partialPath. Could be 0 or more.
     * @param partialPath
     * @return the asset to use.
     * @throws RuntimeException Implementations are free to throw an exception, for example if they cannot find an adequate asset.
     */
    String resolve(SortedMap<String, String> fullPaths, String partialPath) throws RuntimeException;
}
