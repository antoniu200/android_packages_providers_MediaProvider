package com.sonymobile.media;

import android.net.Uri;
import java.util.List;

/* loaded from: classes.dex */
public final class SomcMediaStore {
    private static String versionCache = null;

    public static Uri makeMediaStoreUri(Uri srcUri, String version) {
        String authority = srcUri.getAuthority();
        List<String> segs = srcUri.getPathSegments();
        if (segs.size() < 2) {
            return null;
        }
        if (version != null && Integer.parseInt(version) >= 400) {
            if (!authority.equals("somcmedia") || !segs.get(1).equals("extended_file")) {
                return null;
            }
            Uri dstUri = Uri.parse(srcUri.toString().replaceFirst("extended_file", "file"));
            return Uri.parse(dstUri.toString().replaceFirst("somcmedia", "media"));
        }
        if (!authority.equals("media") || !segs.get(1).equals("extended_file")) {
            return null;
        }
        Uri dstUri2 = Uri.parse(srcUri.toString().replaceFirst("extended_file", "file"));
        return dstUri2;
    }
}