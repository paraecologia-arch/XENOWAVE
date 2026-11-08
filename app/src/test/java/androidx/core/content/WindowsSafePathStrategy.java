package androidx.core.content;

import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only PathStrategy for Robolectric on Windows.
 *
 * androidx FileProvider compares canonical paths with a hard-coded forward
 * slash ("root/") when deciding whether a file belongs to a root. On Android
 * canonical paths already use '/', so the production implementation is fine;
 * on Windows they use '\' and every real FileProvider URI fails. This strategy
 * implements the same contract with separator normalization while the test
 * still calls the real FileProvider.getUriForFile entrypoint.
 */
public final class WindowsSafePathStrategy implements FileProvider.PathStrategy {
    private final String authority;
    private final Map<String, File> roots = new LinkedHashMap<>();

    public WindowsSafePathStrategy(String authority) {
        this.authority = authority;
    }

    public void addRoot(String name, File root) {
        roots.put(name, root);
    }

    @Override
    public Uri getUriForFile(File file) {
        final String path = normalized(file);
        Map.Entry<String, File> best = null;
        String bestRoot = null;
        for (Map.Entry<String, File> entry : roots.entrySet()) {
            final String root = normalized(entry.getValue());
            if ((path.equals(root) || path.startsWith(root + "/"))
                    && (bestRoot == null || root.length() > bestRoot.length())) {
                best = entry;
                bestRoot = root;
            }
        }
        if (best == null || bestRoot == null) {
            throw new IllegalArgumentException(
                    "Failed to find configured root that contains " + path);
        }
        final String relative = path.substring(bestRoot.length()).replaceFirst("^/", "");
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(best.getKey())
                .appendEncodedPath(relative)
                .build();
    }

    @Override
    public File getFileForUri(Uri uri) {
        final java.util.List<String> segments = uri.getPathSegments();
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Missing root name: " + uri);
        }
        final File root = roots.get(segments.get(0));
        if (root == null) {
            throw new IllegalArgumentException("Unknown root: " + uri);
        }
        final File file = new File(
                root,
                String.join(File.separator, segments.subList(1, segments.size())));
        final String path = normalized(file);
        final String rootPath = normalized(root);
        if (!(path.equals(rootPath) || path.startsWith(rootPath + "/"))) {
            throw new SecurityException("Resolved path jumped beyond configured root");
        }
        return file;
    }

    private static String normalized(File file) {
        try {
            return file.getCanonicalPath().replace('\\', '/');
        } catch (IOException error) {
            return file.getAbsolutePath().replace('\\', '/');
        }
    }
}
