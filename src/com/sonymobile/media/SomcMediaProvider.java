package com.sonymobile.media;

import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/* loaded from: classes.dex */
public class SomcMediaProvider extends ContentProvider {
    private static final boolean DEBUG = true;
    private static final byte[] SOUND_PHOTO_ID  = new byte[] { 'S', 'P', 'F', 0 };// "SPF\0"
    private static final int JPEG_MARKER_SOI    = 0xFFD8;                         // file header
    private static final int JPEG_MARKER_PREFIX = 0xFF;                           // 0xFF lead-in
    private static final int JPEG_MARKER_APP3   = 0xE3;                           // -29 in byte
    private static final int JPEG_MARKER_SOS    = 0xDA;                           // -38 in byte
    private static final String MIME_TYPE_JPEG = "image/jpeg";
    private static final String TAG = "SomcMediaProvider";
    private static final UriMatcher URI_MATCHER = new UriMatcher(-1);             // equals check
    private final Object mDbLock = new Object();

    private volatile DatabaseHelper mDBHelper = null;
    private volatile SomcFileType mSomcFileType = null;
    private boolean mIsHdrSupported = isHdrSupported();
    
    private volatile boolean mInitialized = false;
    private volatile ContentProvider mMediaProvider = null;
    
    private interface SomcCustomFunction {
    	void callback(String[] args);
    }

    private boolean isReadyNoBlock() {
        DatabaseHelper h = mDBHelper;
        return h != null && mSomcFileType != null && h.triggersReady();
    }

    private final SomcCustomFunction mSomcFileTypeCallback = new SomcCustomFunction() { // from class: com.sonymobile.media.SomcMediaProvider.1
        public void callback(String[] args) {
            if (!isReadyNoBlock()) {
                return;
            }
            try {
                SomcMediaProvider.this.mDBHelper.runWithoutTransaction(db -> {
                    String rowId = args[0];
                    try (Cursor cursor = db.rawQuery(
                            "SELECT _data, mime_type, bucket_id FROM files_with_ext " +
                            "WHERE _id = ? AND somctype = -1;",
                            new String[]{rowId})) {
                        if (cursor.moveToFirst() && cursor.getString(0) != null
                                && new File(cursor.getString(0)).exists()) {
                            if (SomcMediaProvider.DEBUG) {
                                Log.d(TAG, "updateFileType will be called. rowId=" + rowId);
                            }
                            SomcMediaProvider.this.updateFileType(
                                    db, cursor.getString(0), cursor.getString(1), rowId);
                            SomcMediaProvider.this.updateCover(
                                    db, cursor.getString(0), cursor.getString(1),
                                    cursor.getString(2), true);
                            try {
                                SomcMediaProvider.this.updateHdrInfo(
                                        db, cursor.getString(0), cursor.getString(1), rowId);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                    return null;
                });
            } catch (Exception e) {
                Log.e(TAG, "callback failed.", e);
            }
        }
    };
    private final SomcCustomFunction mCoverRemovedCallback = new SomcCustomFunction() { // from class: com.sonymobile.media.SomcMediaProvider.2
        public void callback(String[] args) {
            if (!isReadyNoBlock()) {
                return;
            }
            try {
                SomcMediaProvider.this.mDBHelper.runWithoutTransaction(db -> {
                    SomcMediaProvider.this.updateCover(db, args[1], args[2], args[0], false);
                    return null;
                });
            } catch (Exception e) {
                Log.e(TAG, "callback failed.", e);
            }
        }
    };

    static {
        URI_MATCHER.addURI("somcmedia", "*/version", 100);
        URI_MATCHER.addURI("somcmedia", "*/extended_file", 101);
        URI_MATCHER.addURI("somcmedia", "*/extended_file/#", 102);
    }

    private static class Field {
        String mDefaultValue;
        boolean mNotNull;
        FieldType mType;

        enum FieldType {
            INTEGER,
            TEXT,
            DOUBLE
        }
        
        private static FieldType toFieldType(String type) {
            if (type == null) // avoid NPE from toUpperCase
                return FieldType.TEXT;
            
            String t = type.toUpperCase(Locale.ROOT);
            if (t.contains("INT"))
                return FieldType.INTEGER;
            if (t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB"))
                return FieldType.DOUBLE; // treat REAL/DOUBLE/FLOAT same
            if (t.contains("TEXT") || t.contains("BLOB") || t.contains("CHAR") || t.contains("CLOB") || t.isEmpty())
                return FieldType.TEXT;
            return FieldType.TEXT; // default
        }

        Field(FieldType type, boolean notNull, String defaultValue) {
            this.mType = type;
            this.mNotNull = notNull;
            if (defaultValue != null && defaultValue.length() > 0) {
                this.mDefaultValue = defaultValue;
            } else {
                this.mDefaultValue = null;
            }
        }

        static String buildUpdateQueryPart(LinkedHashMap<String, Field> extFields) {
            if (extFields == null || extFields.isEmpty()) // Null-guard
                return "";
            StringBuilder sb = new StringBuilder(4 * extFields.size());
            for (String name : extFields.keySet()) {
                sb.append(",");
                sb.append(name);
                sb.append("=NEW.");
                sb.append(name);
            }
            return sb.substring(1);
        }

        static LinkedHashMap<String, Field> parseTable(SQLiteDatabase db, String table, String skip) {
            Cursor mCursor = null;
            try {
                final String pragma;
                int dot = table.indexOf('.');
                if (dot > 0 && dot < table.length() - 1) {
                    String schema = table.substring(0, dot);
                    String tbl    = table.substring(dot + 1);
                    pragma = "PRAGMA " + schema + ".table_info(" + tbl + ")";
                } else {
                    pragma = "PRAGMA table_info(" + table + ")";
                }
                mCursor = db.rawQuery(pragma, null);
                if (mCursor != null && mCursor.getCount() != 0) {
                    LinkedHashMap<String, Field> map = new LinkedHashMap<>(mCursor.getCount());
                    while (mCursor.moveToNext()) {
                        boolean z = true;
                        String fieldName = mCursor.getString(1);
                        if (skip == null || !skip.equals(fieldName)) {
                            FieldType fieldTypeValueOf = toFieldType(mCursor.getString(2));
                            if (mCursor.getInt(3) != 1) {
                                z = false;
                            }
                            map.put(fieldName, new Field(fieldTypeValueOf, z, mCursor.getString(4)));
                        }
                    }
                    return map;
                }
                if (mCursor != null) {
                    mCursor.close();
                }
                return new LinkedHashMap<>(0);
            } finally {
                if (mCursor != null) {
                    mCursor.close();
                }
            }
        }
    }

    private final class DatabaseHelper {
        private SomcCustomFunction mCb1;
        private SomcCustomFunction mCb2;
        private Context mContext;
        private volatile com.android.providers.media.DatabaseHelper mOrgDBHelper;
        private volatile boolean mTriggersReady;

        public DatabaseHelper(Context ctx, SomcCustomFunction cb1, SomcCustomFunction cb2) {
            mCb1 = cb1;
            mCb2 = cb2;
            mContext = ctx;
        }

        boolean triggersReady() {
            return mTriggersReady;
        }

        public <T> T runWithoutTransaction(java.util.function.Function<SQLiteDatabase, T> operation) {
            com.android.providers.media.DatabaseHelper helper = mOrgDBHelper;
            if (helper == null) {
                helper = getMediaProviderDBHelper();
            }
            return (helper != null) ? helper.runWithoutTransaction(operation) : null;
        }

        private com.android.providers.media.DatabaseHelper getMediaProviderDBHelper() {
            synchronized (this) {
                if (mOrgDBHelper != null) return mOrgDBHelper;
                final ContentProvider mp = SomcMediaProvider.this.getMediaProvider();
                if (mp == null) return null;
                try {
                    Class<?> cls = mp.getContext().getClassLoader()
                            .loadClass("com.android.providers.media.MediaProvider");
                    Method method = cls.getDeclaredMethod("getDatabaseForUri", Uri.class);
                    method.setAccessible(true);
                    final com.android.providers.media.DatabaseHelper helper =
                            (com.android.providers.media.DatabaseHelper) method.invoke(
                                    mp, MediaStore.Files.getContentUri("external"));

                    mOrgDBHelper = helper;

                    helper.runWithoutTransaction(orgDB -> {
                        orgDB.setCustomScalarFunction("_SOMC_FILETYPE_CB",
                                new java.util.function.UnaryOperator<String>() {
                                    public String apply(String arg) {
                                        mCb1.callback(new String[]{arg});
                                        return "";
                                    }
                                });
                        orgDB.setCustomScalarFunction("_COVER_REMOVED_CB",
                                new java.util.function.UnaryOperator<String>() {
                                    public String apply(String packed) {
                                        mCb2.callback(packed == null
                                                ? new String[0] : packed.split("\u0001", -1));
                                        return "";
                                    }
                                });
                        orgDB.beginTransaction();
                        try {
                            createInsertTriggers(orgDB);
                            createUpdateTriggers(orgDB);
                            createDeleteTriggers(orgDB);
                            orgDB.setTransactionSuccessful();
                        } finally {
                            orgDB.endTransaction();
                        }
                        return null;
                    });
                    mTriggersReady = true;
                    Log.i(TAG, "ENSURE DATABASE SUCCESS !!");
                    return helper;
                } catch (Exception e) {
                    mOrgDBHelper = null;
                    Log.e(TAG, "ENSURE DATABASE FAILURE !!", e);
                    return null;
                }
            }
        }

        private void createUpdateTriggers(SQLiteDatabase db) throws SQLException {
            db.execSQL("DROP TRIGGER IF EXISTS files_after_update;");
            db.execSQL(
                    "CREATE TEMPORARY TRIGGER files_after_update " +
                    "AFTER UPDATE ON files BEGIN " +
                    "SELECT _SOMC_FILETYPE_CB(OLD._id);" +
                    "END;"
            );
        }

        private void createDeleteTriggers(SQLiteDatabase db) throws SQLException {
            db.execSQL("DROP TRIGGER IF EXISTS files_after_delete;");
            db.execSQL("CREATE TEMPORARY TRIGGER files_after_delete AFTER DELETE ON files BEGIN DELETE FROM files_ext WHERE files_id = old._id;END;");
            db.execSQL("DROP TRIGGER IF EXISTS files_before_delete");
            db.execSQL("CREATE TEMPORARY TRIGGER files_before_delete BEFORE DELETE ON files WHEN old.media_type<>0 BEGIN SELECT _COVER_REMOVED_CB(CAST(bucket_id AS TEXT) || CAST(X'01' AS TEXT) || IFNULL(_data,'') || CAST(X'01' AS TEXT) || IFNULL(mime_type,'')) FROM files_with_ext WHERE _id=old._id AND somccategory=3;END;");
        }

        private void createInsertTriggers(SQLiteDatabase db) throws SQLException {
            db.execSQL("DROP TRIGGER IF EXISTS files_after_insert;");
            db.execSQL("CREATE TEMPORARY TRIGGER files_after_insert AFTER INSERT ON files BEGIN INSERT OR IGNORE INTO files_ext (files_id) VALUES ((SELECT NEW._id));SELECT _SOMC_FILETYPE_CB(NEW._id);END;");
        }
    }

    private final class SomcFileType {
        private ArrayList<SomcType> mSomcCoverTypes;
        private ArrayList<SomcType> mSomcSequenceTypes;
        private ArrayList<SomcType> mSomcTypes;

        private class SomcType {
            final int mId;
            final String mMimeType;
            final Pattern mPattern;
            final String mSqlPattern;

            SomcType(int id, String mimeType, String sqlPattern, String folderPathRegExp) {
                this.mId = id;
                this.mMimeType = mimeType;
                this.mSqlPattern = sqlPattern;
                this.mPattern = Pattern.compile("(?i)" + folderPathRegExp);
            }

            boolean matches(String path, String mime) {
                if (this.mMimeType.equals("")) {
                    boolean match = this.mPattern.matcher(path).matches();
                    return match;
                }
                boolean match2 = this.mPattern.matcher(path).matches() && this.mMimeType.equals(mime);
                return match2;
            }
        }

        public SomcFileType() {
            initSomcTypes();
        }

        private void initSomcTypes() {
            this.mSomcTypes = new ArrayList<>();
            this.mSomcSequenceTypes = new ArrayList<>();
            this.mSomcCoverTypes = new ArrayList<>();
            initSomcType(0, "", "", 0);
            initSomcType(2, "DCIM/XPERIA/BURST", "image/jpeg", 3);
            initSomcType(4, "DCIM/XPERIA/TIMESHIFT", "image/jpeg", 3);
            initSomcType(5, "DCIM/XPERIA/INFO_EYE", "image/jpeg", 1);
            initSomcType(6, "DCIM/XPERIA/SOCIAL_LIVE", "video/mp4", 1);
            initSomcType(7, "DCIM/XPERIA/AR_EFFECT", "", 1);
            initSomcType(8, "DCIM/XPERIA/MOTIONGRAPH", "image/gif", 1);
            initSomcType(9, "DCIM/XPERIA/BACKGROUND_DEFOCUS", "image/jpeg", 1);
            initSomcType(10, "DCIM/XPERIA/WIKITUDE", "image/jpeg", 1);
            initSomcType(11, "DCIM/XPERIA/TIMESHIFT_VIDEO/120F", "video/mp4", 1);
            initSomcType(12, "DCIM/XPERIA/TIMESHIFT_VIDEO", "video/mp4", 1);
            initSomcType(13, "", "", 1);
            initSomcType(14, "DCIM/XPERIA/HIGHLIGHT_MOVIE", "video/mp4", 1);
            initSomcType(42, "", "image/jpeg", 1);
            initSomcType(129, "DCIM/XPERIA/BURST", "image/jpeg", 2);
            initSomcType(130, "DCIM/XPERIA/TIMESHIFT", "image/jpeg", 2);
        }

        private void initSomcType(int id, String path, String mimeType, int category) {
            switch (category) {
                case 1:
                    if (SomcMediaProvider.DEBUG) {
                        Log.d(TAG, "Found/added single type at " + path);
                    }
                    this.mSomcTypes.add(new SomcType(id, mimeType, "%_/" + path + "/%_.%_", ".*/" + path + "/[^/]*\\.[a-z0-9_]*$"));
                    break;
                case 2:
                    if (SomcMediaProvider.DEBUG) {
                        Log.d(TAG, "Found/added sequence type at " + path);
                    }
                    this.mSomcSequenceTypes.add(new SomcType(id, mimeType, "%_/" + path + "/%_/%_.%_", ".*/" + path + "/[^/]*/[^/]*\\.[a-z0-9_]*$"));
                    break;
                case 3:
                    if (SomcMediaProvider.DEBUG) {
                        Log.d(TAG, "Found/added cover type at " + path);
                    }
                    this.mSomcCoverTypes.add(new SomcType(id, mimeType, "%_/" + path + "/%_/%_.%_", ".*/" + path + "/[^/]*/[^/]*\\.[a-z0-9_]*$"));
                    break;
            }
        }

        public int findSomcType(ArrayList<SomcType> types, String path, String mimeType) {
            Iterator<SomcType> it = types.iterator();
            while (it.hasNext()) {
                SomcType type = it.next();
                if (type.matches(path, mimeType)) {
                    return type.mId;
                }
            }
            return -1;
        }
    }

    private boolean isVolumeReady(Context context, String volumeName) {
        return MediaStore.getExternalVolumeNames(context).contains(volumeName);
    }
    
    private boolean isSchemaAlive() {
        if (mDBHelper == null)
            return false;

        try {
            Boolean alive = mDBHelper.runWithoutTransaction(db -> {
                try (Cursor c = db.rawQuery(
                        "SELECT 1 FROM sqlite_master " +
                        "WHERE type='view' AND name='files_with_ext'",
                        null)) {
                    return c.moveToFirst();
                }
            });
            return Boolean.TRUE.equals(alive);
        } catch (Exception e) {
            // If anything goes wrong, treat schema as missing
            return false;
        }
    }

    private boolean ensureInitialized() {
        if (mDBHelper != null && mSomcFileType != null && isSchemaAlive()) {
            return true;
        }

        final Context ctx = getContext();
        if (ctx == null) {
            Log.w(TAG, "ensureInitialized(): No context yet.");
            return false;
        }

        if (!isVolumeReady(ctx, "external_primary")) {
            Log.i(TAG, "ensureInitialized(): 'external_primary' not ready, deferring.");
            return false;
        }

        synchronized (mDbLock) {
            if (mDBHelper != null && mSomcFileType != null && isSchemaAlive()) {
                return true;
            }

            Log.i(TAG, "ensureInitialized(): Creating SomcFileType + DatabaseHelper.");
            mSomcFileType = new SomcFileType();
            mDBHelper = new DatabaseHelper(ctx, mSomcFileTypeCallback, mCoverRemovedCallback);

            if (mDBHelper == null) {
                Log.e(TAG, "ensureInitialized(): Failed to create DatabaseHelper.");
                return false;
            }
            if (!isSchemaAlive()) {
                Log.e(TAG, "ensureInitialized(): Couldn't set up database.");
                return false;
            }

            return true;
        }
    }

    @Override // android.content.ContentProvider
    public boolean onCreate() {
        Log.i(TAG, "onCreate(): Begin");
        ensureInitialized();
        Log.i(TAG, "onCreate(): End (mDBHelper=" + (mDBHelper != null) + ")");
        return true;
    }

    @Override // android.content.ContentProvider
    public String getType(Uri uri) {
        ContentProvider mp = getMediaProvider();
        if (mp == null) {
            return null;
        }
        String type = mp.getType(SomcMediaStore.makeMediaStoreUri(uri, String.valueOf(401)));
        return type;
    }

    @Override // android.content.ContentProvider
    public int bulkInsert(Uri uri, ContentValues[] values) {
        throw new UnsupportedOperationException("bulkInsert is unsupported " + uri);
    }

    @Override // android.content.ContentProvider
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations) throws OperationApplicationException {
        if (DEBUG) {
            Log.d(TAG, "applyBatch: " + operations.toString());
        }
        ContentProviderResult[] result = super.applyBatch(operations);
        if (result != null) {
            Uri newAuthority = Uri.parse("content://somcmedia");
            Uri oldAuthority = Uri.parse("content://media");
            getContext().getContentResolver().notifyChange(newAuthority, null);
            getContext().getContentResolver().notifyChange(oldAuthority, null);
        }
        return result;
    }

    @Override // android.content.ContentProvider
    public Cursor query(Uri uri, String[] proj, String select, String[] selectArgs, String sort) {
        if (!ensureInitialized()) {
            String[] cols = (proj != null && proj.length > 0)
                    ? proj
                    : new String[] { "_id" };
            return new MatrixCursor(cols, 0);
        }
        String str;
        String select2;
        String nonotify = "";
        Cursor mCursor = null;
        if (DEBUG) {
            StringBuilder sb = new StringBuilder();
            sb.append("query: uri=");
            sb.append(uri);
            sb.append(", proj=");
            sb.append(Arrays.toString(proj));
            sb.append(", select=");
            sb.append(select);
            sb.append(", selectArgs=");
            sb.append(Arrays.toString(selectArgs));
            sb.append(", sort=");
            str = sort;
            sb.append(str);
            Log.d(TAG, sb.toString());
        } else {
            str = sort;
        }
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        String limit = uri.getQueryParameter("limit");
        int match = URI_MATCHER.match(uri);
        if (match == 100) {
            MatrixCursor cursor = new MatrixCursor(new String[]{"version"});
            cursor.addRow(new Integer[]{401});
            return cursor;
        }
        switch (match) {
            case 101:
                break;
            case 102:
                qb.appendWhere("_id = " + uri.getLastPathSegment());
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        qb.setTables("files_with_ext");
        if (uri.getQueryParameter("distinct") != null) {
            qb.setDistinct(true);
        }
        if (select != null && select.length() > 0) {
            select2 = "(media_type IS NOT NULL OR old_id IS NULL) AND " + select;
        } else {
            select2 = "(media_type IS NOT NULL OR old_id IS NULL)";
        }
        mCursor = SomcMediaProvider.this.mDBHelper.runWithoutTransaction(db ->
                qb.query(db, proj, select2, selectArgs, null, null, str, limit));
        if (mCursor != null) {
            nonotify = uri.getQueryParameter("nonotify");
            if (nonotify == null || !nonotify.equals("1"))
            	mCursor.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return mCursor;
    }

    @Override // android.content.ContentProvider
    public Uri insert(Uri uri, ContentValues initVals) {
        throw new UnsupportedOperationException("insert is unsupported " + uri);
    }

    @Override // android.content.ContentProvider
    public int delete(Uri uri, String userWhere, String[] whereArgs) {
        if (!ensureInitialized()) {
            return 0;
        }
        if (DEBUG) {
            Log.d(TAG, "delete: uri=" + uri
                    + ", userWhere=" + userWhere
                    + ", whereArgs=" + java.util.Arrays.toString(whereArgs));
        }

        int count = 0;
        final int match = URI_MATCHER.match(uri);

        switch (match) {
            case 101: // collection
            case 102: // single item
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        final ContentProvider mp = getMediaProvider();
        if (mp == null) {
            return 0;
        }

        String where = getWhere(uri, userWhere);
        if (where != null && where.length() > 0) {
            where = "(media_type IS NOT NULL OR old_id IS NULL) AND " + where;
        } else {
            where = "(media_type IS NOT NULL OR old_id IS NULL)";
        }
        final String finalWhere = where;

        // Never call back into MediaProvider.delete() while holding our schema read lock.
        // Snapshot the matching IDs first, then release the DB operation.
        final ArrayList<Long> rowIds = SomcMediaProvider.this.mDBHelper
                .runWithoutTransaction(db -> {
                    final ArrayList<Long> ids = new ArrayList<>();
                    try (Cursor c = db.query(
                            getTable(uri),
                            new String[] { "_id" },
                            finalWhere,
                            whereArgs,
                            null,
                            null,
                            null)) {
                        while (c.moveToNext()) {
                            ids.add(c.getLong(0));
                        }
                    }
                    return ids;
                });

        if (rowIds == null) {
            return 0;
        }

        for (Long rowId : rowIds) {
            final Uri orgUri;
            if (match == 102) {
                orgUri = SomcMediaStore.makeMediaStoreUri(uri, String.valueOf(401));
            } else {
                final Uri base = SomcMediaStore.makeMediaStoreUri(uri, String.valueOf(401));
                orgUri = Uri.withAppendedPath(base, String.valueOf(rowId));
            }
            count += mp.delete(orgUri, /* where */ null, /* whereArgs */ null);
        }

        if (count > 0) {
            getContext().getContentResolver().notifyChange(uri, null);
        }

        return count;
    }

    @Override // android.content.ContentProvider
    public int update(Uri uri, ContentValues values, String userWhere, String[] whereArgs) {
        if (!ensureInitialized()) {
            return 0;
        }
        if (DEBUG) {
            Log.d(TAG, "update: uri=" + uri
                    + ", initVals=" + values
                    + ", userWhere=" + userWhere
                    + ", whereArgs=" + Arrays.toString(whereArgs));
        }

        final int match = URI_MATCHER.match(uri);
        switch (match) {
            case 101: // 101: collection
            case 102: // 102: single item
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        // Compose WHERE: restrict to rows owned by us, then append caller's clause
        String where = getWhere(uri, userWhere);
        if (where != null && where.length() > 0) {
            where = "(media_type IS NOT NULL OR old_id IS NULL) AND " + where;
        } else {
            where = "(media_type IS NOT NULL OR old_id IS NULL)";
        }
        final String finalWhere = where;

        final Integer updatedCount = SomcMediaProvider.this.mDBHelper
                .runWithoutTransaction(db -> {
                    int matched = 0;
                    try (Cursor c = db.query(
                            getTable(uri),
                            new String[] { "COUNT(_id)" },
                            finalWhere,
                            whereArgs,
                            null,
                            null,
                            null)) {
                        if (c.moveToFirst()) {
                            matched = c.getInt(0);
                        }
                    }

                    db.update(getTable(uri), values, finalWhere, whereArgs);
                    return matched;
                });
        final int count = (updatedCount != null) ? updatedCount : 0;

        // Notify mirrors + caller URI if anything changed
        if (count > 0) {
            final ContentResolver cr = getContext().getContentResolver();
            final Uri msUri = SomcMediaStore.makeMediaStoreUri(uri, String.valueOf(401)); // 401
            cr.notifyChange(msUri, null);
            cr.notifyChange(uri, null);
        }

        return count;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ContentProvider getMediaProvider() {
        ContentProvider mp = mMediaProvider;
        if (mp != null) {
            return mp;
        }
        try (ContentProviderClient client = getContext().getContentResolver()
                .acquireContentProviderClient("media")) {
            mp = (client != null) ? client.getLocalContentProvider() : null;
        }
        if (mp != null) {
            mMediaProvider = mp;
        }
        return mp;
    }

    private static String getTable(Uri uri) {
        switch (URI_MATCHER.match(uri)) {
            case 101:
            case 102:
                return "files_with_ext";
            default:
                return null;
        }
    }

    private static String getWhere(Uri uri, String userWhere) {
        String where = null;
        if (URI_MATCHER.match(uri) == 102) {
            where = "_id = " + uri.getLastPathSegment();
        }
        if (!TextUtils.isEmpty(userWhere)) {
            if (!TextUtils.isEmpty(where)) {
                return where + " AND (" + userWhere + ")";
            }
            return userWhere;
        }
        return where;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateFileType(SQLiteDatabase db, String path, String mimeType, String rowId) {
        if (!isReadyNoBlock()) {
            return;
        }
        if (mimeType == null && path != null) {
            mimeType = MediaFile.getMimeTypeForFile(path);
        }
        if (path != null) {
            int type = -1;
            int category = -1;
            if (-1 == -1 && (type = this.mSomcFileType.findSomcType(this.mSomcFileType.mSomcTypes, path, mimeType)) != -1) {
                category = 1;
            }
            if (type == -1 && (type = this.mSomcFileType.findSomcType(this.mSomcFileType.mSomcSequenceTypes, path, mimeType)) != -1) {
                category = 2;
            }
            if (type == -1 && isSoundPhotoImage(path, mimeType)) {
                type = 42;
                category = 1;
            }
            if (type == -1) {
                type = 0;
                category = 0;
            }
            if (type != -1) {
                if (DEBUG) {
                    Log.d(TAG, path + ": somctype=" + type + ", category=" + category);
                }
                ContentValues vals = new ContentValues();
                vals.put("somctype", Integer.valueOf(type));
                vals.put("somccategory", Integer.valueOf(category));
                db.update("files_ext", vals, "files_id=?", new String[]{rowId});
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateCover(SQLiteDatabase db,
                            String path,
                            String mimeType,
                            String bucketId,
                            boolean demoteCurrent) {
        if (!isReadyNoBlock()) {
            return;
        }
        // Normalize mimeType.
        if (mimeType == null && path != null) {
            mimeType = MediaFile.getMimeTypeForFile(path);
        }
        // Only care about media files.
        if (!MediaFile.isMimeTypeMedia(mimeType)) {
            return;
        }

        // Categories used by files_ext.somccategory
        final int CAT_SEQUENCE = 2; // sequence candidates (somctype 129/130)  [upgrade SQL]
        final int CAT_COVER    = 3; // album/cover images (somctype 2/4)      [upgrade SQL]

        // 1) If requested, demote the current cover (category 3) to a sequence candidate (category 2).
        if (demoteCurrent) {
            // Pick a representative sequence type for this file.
            final int seqType = mSomcFileType.findSomcType(
                    mSomcFileType.mSomcSequenceTypes, path, mimeType); // access$700
            Cursor c = null;
            try {
                c = db.query(
                        "files_with_ext",
                        new String[] { "_id" },
                        "bucket_id=? AND somccategory=?",
                        new String[] { bucketId, Integer.toString(CAT_COVER) },
                        null, null,
                        "_data ASC",  // deterministic order; pick the middle
                        null);
                if (c.moveToFirst()) {
                    final int mid = c.getCount() / 2;
                    if (c.move(mid)) {
                        ContentValues v = new ContentValues();
                        v.put("somctype", Integer.valueOf(seqType));
                        v.put("somccategory", Integer.valueOf(CAT_SEQUENCE));
                        db.update("files_ext", v, "files_id=?", new String[] { c.getString(0) });
                    }
                }
            } finally {
                if (c != null) c.close();
            }
        }

        // 2) Promote a sequence candidate (category 2) to be the new cover (category 3).
        {
            // Pick a representative cover type for this file.
            final int coverType = mSomcFileType.findSomcType(
                    mSomcFileType.mSomcCoverTypes, path, mimeType); // access$800
            Cursor c = null;
            try {
                c = db.query(
                        "files_with_ext",
                        new String[] { "_id" },
                        "bucket_id=? AND somccategory=?",
                        new String[] { bucketId, Integer.toString(CAT_SEQUENCE) },
                        null, null,
                        "_data ASC",
                        null);
                if (c.moveToFirst()) {
                    final int mid = c.getCount() / 2;
                    if (c.move(mid)) {
                        ContentValues v = new ContentValues();
                        v.put("somctype", Integer.valueOf(coverType));
                        v.put("somccategory", Integer.valueOf(CAT_COVER));
                        db.update("files_ext", v, "files_id=?", new String[] { c.getString(0) });
                    }
                }
            } finally {
                if (c != null) c.close();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateHdrInfo(SQLiteDatabase db, String path, String mimeType, String rowId) throws IOException {
        if (!this.mIsHdrSupported) {
            return;
        }
        if (mimeType == null && path != null) {
            mimeType = MediaFile.getMimeTypeForFile(path);
        }
        if (path != null) {
            ContentValues vals = new ContentValues();
            vals.put("is_hdr", Boolean.valueOf(isHdrVideo(path, mimeType)));
            db.update("files_ext", vals, "files_id=?", new String[]{rowId});
        }
    }

    private static boolean isHdrVideo(String path, String mimeType) {
        if (mimeType == null) return false;
        switch (mimeType) {
            case "video/mp4":
            case "video/mpeg":
            case "video/webm":
                break;
            default:
                return false;
        }

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            int colorStandard = parseIntSafe(mmr.extractMetadata(1002), -1); // COLOR_STANDARD
            int colorTransfer = parseIntSafe(mmr.extractMetadata(1003), -1); // COLOR_TRANSFER
            return colorStandard == 6 && (colorTransfer == 6 || colorTransfer == 7);
        } catch (RuntimeException e) {
            return false;
        } finally {
            try { mmr.release(); } catch (RuntimeException ignore) {}
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null)
            return fallback;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isSoundPhotoImage(String path, String mimeType) {
        if (mimeType == null || !MIME_TYPE_JPEG.equals(mimeType)) {
            return false;
        }

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path, "r")) {
            // Verify JPEG SOI
            if (raf.readUnsignedShort() != JPEG_MARKER_SOI) {
                // Matches fallback behavior (throws -> caught -> returns false)
                throw new IllegalStateException("no JPEG file marker");
            }

            // Walk segments until SOS; return true if we see APP3/"SPF\0"
            while (true) {
                int prefix = raf.readUnsignedByte();
                if (prefix != JPEG_MARKER_PREFIX) {
                    throw new IllegalStateException("bad segment");
                }
                int marker = raf.readUnsignedByte();
                long segStart = raf.getFilePointer();
                int len = raf.readUnsignedShort();
                if (len <= 0) {
                    throw new IllegalStateException("bad segment length");
                }

                if (marker == JPEG_MARKER_APP3) {
                    byte[] magic = new byte[4];
                    raf.readFully(magic);
                    if (java.util.Arrays.equals(SOUND_PHOTO_ID, magic)) {
                        return true; // found "SPF\0" in APP3
                    }
                }

                // Stop at Start Of Scan
                if (marker == JPEG_MARKER_SOS) {
                    break;
                }

                // Skip to end of this segment (length includes its own 2-byte length field)
                raf.seek(segStart + len);
            }
        } catch (Exception ignore) {
            // Any parse/IO problems => not a Sound Photo JPEG
        }
        return false;
    }

    private static boolean isHdrSupported() {
        MediaCodecList list = new MediaCodecList(1);
        MediaCodecInfo[] infos = list.getCodecInfos();
        for (MediaCodecInfo info : infos) {
            if (!info.isEncoder()) {
                String[] types = info.getSupportedTypes();
                for (String type : types) {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    for (MediaCodecInfo.CodecProfileLevel profile : caps.profileLevels) {
                        if (type.equals("video/hevc") && profile.profile == 4096) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    private static final class MediaFile {
        static String getMimeTypeForFile(String path) {
            if (path == null)
                return null;
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot + 1 >= path.length())
                return null;
            String ext = path.substring(dot + 1).toLowerCase(Locale.US);
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
        static boolean isMimeTypeMedia(String mime) {
            if (mime == null)
                return false;
            if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))
                return true;
            
            // Keep AOSP9 parity for playlists (minimal set)
            switch (mime) {
                case "application/vnd.apple.mpegurl":
                case "application/x-mpegurl":
                case "audio/mpegurl":
                case "audio/x-mpegurl":
                case "application/x-scpls":
                case "application/pls+xml":
                case "application/xspf+xml":
                case "application/vnd.ms-wpl":
                    return true;
                default:
                    return false;
            }
        }
    }
}
