package org.webjars;

import static org.webjars.WebJarAssetLocator.WEBJARS_PATH_PREFIX;

import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jboss.vfs.VFS;
import org.jboss.vfs.VirtualFile;
import org.jboss.vfs.VirtualFileFilter;

public class JBossVfsHandler {

    public Set<String> getAssetPaths(URL url, final Pattern filterExpr, final ClassLoader... classLoaders) {
        HashSet<String> assetPaths = new HashSet<String>();
        try {
            final VirtualFile virtualFile = VFS.getChild(url.toURI());
            List<VirtualFile> children = virtualFile.getChildrenRecursively(new VirtualFileFilter() {
                public boolean accepts(VirtualFile file) {
                    if (file.isDirectory()) {
                        return false;
                    }
                    int prefixIndex = file.getPathName().indexOf(WEBJARS_PATH_PREFIX);
                    if (prefixIndex == -1) {
                        return false;
                    }

                    final String relativePath = file.getPathName().substring(prefixIndex);

                    return file.isFile() && filterExpr.matcher(relativePath).matches();
                }
            });
            for (VirtualFile child : children) {
                assetPaths.add(child.getPathName().substring(child.getPathName().indexOf(WEBJARS_PATH_PREFIX)));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return assetPaths;
    }
}
