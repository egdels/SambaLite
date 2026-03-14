package de.schliweb.sambalite.util;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

/**
 * Custom FileProvider that adds the {@code _data} column to query results. Some apps (e.g. Odyssey
 * music player) query the ContentProvider for the {@code _data} column to resolve the file path,
 * which the default FileProvider does not provide. This subclass adds it to prevent crashes in such
 * apps.
 */
public final class OpenFileProvider extends FileProvider {

  @Nullable
  @Override
  public Cursor query(
      @NonNull Uri uri,
      @Nullable String[] projection,
      @Nullable String selection,
      @Nullable String[] selectionArgs,
      @Nullable String sortOrder) {
    Cursor cursor = super.query(uri, projection, selection, selectionArgs, sortOrder);
    if (cursor == null) {
      return null;
    }

    // Check if _data column was requested but is missing
    boolean hasDataColumn;
    try {
      cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
      hasDataColumn = true;
    } catch (IllegalArgumentException e) {
      hasDataColumn = false;
    }

    if (hasDataColumn) {
      return cursor;
    }

    // Build a new cursor that includes the _data column
    String[] originalColumns = cursor.getColumnNames();
    String[] newColumns = new String[originalColumns.length + 1];
    System.arraycopy(originalColumns, 0, newColumns, 0, originalColumns.length);
    newColumns[originalColumns.length] = MediaStore.MediaColumns.DATA;

    MatrixCursor newCursor = new MatrixCursor(newColumns, cursor.getCount());

    // Resolve the file path from the URI
    String filePath = null;
    if (getContext() != null) {
      // The URI path after the authority typically maps to the cache path
      // Try to resolve via the last path segment as filename in open_files cache
      String lastSegment = uri.getLastPathSegment();
      if (lastSegment != null) {
        java.io.File cacheDir = new java.io.File(getContext().getCacheDir(), "open_files");
        // The last segment from FileProvider URI is typically "open_cache/<filename>"
        // Extract just the filename
        String fileName = lastSegment;
        if (fileName.contains("/")) {
          fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        }
        java.io.File file = new java.io.File(cacheDir, fileName);
        if (file.exists()) {
          filePath = file.getAbsolutePath();
        }
      }
    }

    while (cursor.moveToNext()) {
      Object[] row = new Object[newColumns.length];
      for (int i = 0; i < originalColumns.length; i++) {
        int type = cursor.getType(i);
        switch (type) {
          case Cursor.FIELD_TYPE_NULL:
            row[i] = null;
            break;
          case Cursor.FIELD_TYPE_INTEGER:
            row[i] = cursor.getLong(i);
            break;
          case Cursor.FIELD_TYPE_FLOAT:
            row[i] = cursor.getDouble(i);
            break;
          case Cursor.FIELD_TYPE_STRING:
            row[i] = cursor.getString(i);
            break;
          case Cursor.FIELD_TYPE_BLOB:
            row[i] = cursor.getBlob(i);
            break;
          default:
            row[i] = cursor.getString(i);
            break;
        }
      }
      row[originalColumns.length] = filePath;
      newCursor.addRow(row);
    }
    cursor.close();
    return newCursor;
  }
}
