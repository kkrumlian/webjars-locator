package org.webjars;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Locate WebJar assets. The class is thread safe.
 */
public class WebJarAssetLocator {

    /**
     * The webjar package name.
     */
    public static final String WEBJARS_PACKAGE = "META-INF.resources.webjars";

    /**
     * The path to where webjar resources live.
     */
    public static final String WEBJARS_PATH_PREFIX = "META-INF/resources/webjars";

    private static final int MAX_DIRECTORY_DEPTH = 5;

    private static final WebJarAssetResolver EXACTLY_ONE = new WebJarAssetResolver() {
        public String resolve(SortedMap<String, String> fullPathTail, String partialPath) throws RuntimeException {
            if (fullPathTail.size() == 0) {
                throwNotFoundException(partialPath);
            }

            final String reversePartialPath = reversePath(partialPath);

            final Iterator<Entry<String, String>> fullPathTailIter = fullPathTail
                    .entrySet().iterator();
            final Entry<String, String> fullPathEntry = fullPathTailIter.next();
            if (!fullPathEntry.getKey().startsWith(reversePartialPath)) {
                throwNotFoundException(partialPath);
            }
            final String fullPath = fullPathEntry.getValue();

            if (fullPathTailIter.hasNext()
                    && fullPathTailIter.next().getKey()
                    .startsWith(reversePartialPath)) {
                throw new MultipleMatchesException(
                        "Multiple matches found for "
                                + partialPath
                                + ". Please provide a more specific path, for example by including a version number.");
            }

            return fullPath;
        }

        private String throwNotFoundException(final String partialPath) {
            throw new IllegalArgumentException(
                    partialPath
                            + " could not be found. Make sure you've added the corresponding WebJar and please check for typos.");
        }
    };

    private WebJarAssetResolver assetResolver = EXACTLY_ONE;

    private static void aggregateFile(final File file, final Set<String> aggregatedChildren, final Pattern filterExpr) {
        final String path = file.getPath();
        final String relativePath = path.substring(path.indexOf(WEBJARS_PATH_PREFIX));
        if (filterExpr.matcher(relativePath).matches()) {
            aggregatedChildren.add(relativePath);
        }
    }

    /*
     * Recursively search all directories for relative file paths matching `filterExpr`.
     */
    private static Set<String> listFiles(final File file, final Pattern filterExpr) {
        final Set<String> aggregatedChildren = new HashSet<String>();
        aggregateChildren(file, file, aggregatedChildren, filterExpr, 0);
        return aggregatedChildren;
    }

    private static void aggregateChildren(final File rootDirectory, final File file, final Set<String> aggregatedChildren, final Pattern filterExpr, final int level) {
        if (file.isDirectory()) {
            if (level > MAX_DIRECTORY_DEPTH) {
                throw new IllegalStateException("Got deeper than " + MAX_DIRECTORY_DEPTH + " levels while searching " + rootDirectory);
            }

            for (final File child : file.listFiles()) {
                aggregateChildren(rootDirectory, child, aggregatedChildren, filterExpr, level + 1);
            }
        } else {
            aggregateFile(file, aggregatedChildren, filterExpr);
        }
    }

    /*
     * Return all {@link URL}s defining {@value WebJarAssetLocator#WEBJARS_PATH_PREFIX} directory, either identifying JAR files or plain directories.
     */
    private static Set<URL> listWebjarsParentURLs(final ClassLoader[] classLoaders) {
        final Set<URL> urls = new HashSet<URL>();
        for (final ClassLoader classLoader : classLoaders) {
            try {
                final Enumeration<URL> enumeration = classLoader.getResources(WEBJARS_PATH_PREFIX);
                while (enumeration.hasMoreElements()) {
                    urls.add(enumeration.nextElement());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return urls;
    }

    /*
     * Return all of the resource paths filtered given an expression and a list
     * of class loaders.
     */
    private static Set<String> getAssetPaths(final Pattern filterExpr,
                                             final ClassLoader... classLoaders) {
        final Set<String> assetPaths = new HashSet<String>();
        final Set<URL> urls = listWebjarsParentURLs(classLoaders);
        for (final URL url : urls) {
            if ("file".equals(url.getProtocol())) {
                final File file;
                try {
                    file = new File(url.toURI());
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                final Set<String> paths = listFiles(file, filterExpr);
                assetPaths.addAll(paths);
            } else if ("jar".equals(url.getProtocol())) {
                final JarFile jarFile;
                try {
                    final String path = url.getPath();
                    final File file = new File(URI.create(path.substring(0, path.indexOf("!"))));
                    jarFile = new JarFile(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                final Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    final JarEntry entry = entries.nextElement();
                    final String assetPathCandidate = entry.getName();
                    if (!entry.isDirectory() && filterExpr.matcher(assetPathCandidate).matches()) {
                        assetPaths.add(assetPathCandidate);
                    }
                }
            } else if ("vfs".equals(url.getProtocol())) {
                assetPaths.addAll(new JBossVfsHandler().getAssetPaths(url, filterExpr, classLoaders));
            }
        }
        return assetPaths;
    }

    /**
     * Return a map that can be used to perform index lookups of partial file
     * paths. This index constitutes a key that is the reverse form of the path
     * it relates to. Thus if a partial lookup needs to be performed from the
     * rightmost path components then the key to access can be expressed easily
     * e.g. the path "a/b" would be the map tuple "b/a" -> "a/b". If we need to
     * look for an asset named "a" without knowing the full path then we can
     * perform a partial lookup on the sorted map.
     *
     * @param filterExpr   the regular expression to be used to filter resources that
     *                     will be included in the index.
     * @param classLoaders the class loaders to be considered for loading the resources
     *                     from.
     * @return the index.
     */
    public static SortedMap<String, String> getFullPathIndex(
            final Pattern filterExpr, final ClassLoader... classLoaders) {

        final Set<String> assetPaths = getAssetPaths(filterExpr, classLoaders);

        final SortedMap<String, String> assetPathIndex = new TreeMap<String, String>();
        for (final String assetPath : assetPaths) {
            assetPathIndex.put(reversePath(assetPath), assetPath);
        }

        return assetPathIndex;
    }

    /*
     * Make paths like aa/bb/cc = cc/bb/aa.
     */
    private static String reversePath(String assetPath) {
        final String[] assetPathComponents = assetPath.split("/");
        final StringBuilder reversedAssetPath = new StringBuilder();
        for (int i = assetPathComponents.length - 1; i >= 0; --i) {
            if (reversedAssetPath.length() > 0) {
                reversedAssetPath.append('/');
            }
            reversedAssetPath.append(assetPathComponents[i]);
        }
        return reversedAssetPath.toString();
    }

    final SortedMap<String, String> fullPathIndex;

    /**
     * Convenience constructor that will form a locator for all resources on the
     * current class path.
     */
    public WebJarAssetLocator() {
        this(getFullPathIndex(Pattern.compile(".*"),
                WebJarAssetLocator.class.getClassLoader()));
    }

    /**
     * Establish a locator given an index that it should use.
     *
     * @param fullPathIndex the index to use.
     */
    public WebJarAssetLocator(final SortedMap<String, String> fullPathIndex) {
        this.fullPathIndex = fullPathIndex;
    }

    private String throwNotFoundException(final String partialPath) {
        throw new IllegalArgumentException(
                partialPath
                        + " could not be found. Make sure you've added the corresponding WebJar and please check for typos.");
    }

    /**
     * Given a distinct path within the WebJar index passed in return the full
     * path of the resource.
     *
     * @param partialPath the path to return e.g. "jquery.js" or "abc/someother.js".
     *                    This must be a distinct path within the index passed in.
     * @return a fully qualified path to the resource.
     */
    public String getFullPath(final String partialPath) {

        final String reversePartialPath = reversePath(partialPath);

        final SortedMap<String, String> fullPathTail = fullPathIndex
                .tailMap(reversePartialPath);

        return assetResolver.resolve(fullPathTail, partialPath);
    }

    public SortedMap<String, String> getFullPathIndex() {
        return fullPathIndex;
    }

    /**
     * List assets within a folder.
     *
     * @param folderPath the root path to the folder. Must begin with '/'.
     * @return a set of folder paths that match.
     */
    public Set<String> listAssets(final String folderPath) {
        final Collection<String> allAssets = fullPathIndex.values();
        final Set<String> assets = new HashSet<String>();
        final String prefix = WEBJARS_PATH_PREFIX + folderPath;
        for (final String asset : allAssets) {
            if (asset.startsWith(prefix)) {
                assets.add(asset);
            }
        }
        return assets;
    }

    public void setAssetResolver(WebJarAssetResolver assetResolver) {
        this.assetResolver = assetResolver;
    }
}
