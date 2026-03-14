package de.schliweb.sambalite.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import java.io.File;

/** Utility class for opening files with an appropriate app via Intent.ACTION_VIEW. */
public final class FileOpener {

  private FileOpener() {}

  /**
   * Opens a local file with a matching app via Intent.ACTION_VIEW.
   *
   * @param context Activity context
   * @param file the locally cached file to open
   * @return true if an app was found and the file was opened, false otherwise
   */
  public static boolean openFile(@NonNull Context context, @NonNull File file) {
    String mimeType = MimeTypeUtils.getMimeType(file.getName());
    Uri contentUri =
        FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);

    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setDataAndType(contentUri, mimeType);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

    try {
      context.startActivity(intent);
      return true;
    } catch (android.content.ActivityNotFoundException e) {
      return false;
    }
  }
}
