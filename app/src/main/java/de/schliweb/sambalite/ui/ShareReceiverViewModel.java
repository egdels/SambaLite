package de.schliweb.sambalite.ui;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import javax.inject.Inject;

public class ShareReceiverViewModel extends FileOperationsViewModel {

  private final FileBrowserState state;

  @Inject
  public ShareReceiverViewModel(
      @NonNull SmbRepository smbRepository,
      @NonNull Context context,
      @NonNull FileBrowserState state,
      @NonNull FileListViewModel fileListViewModel,
      @NonNull BackgroundSmbManager backgroundSmbManager) {
    super(smbRepository, context, state, fileListViewModel, backgroundSmbManager);
    this.state = state;
  }

  /**
   * Gets the current connection being used for browsing.
   *
   * @return The current SmbConnection or null if none is set
   */
  public @Nullable SmbConnection getConnection() {
    return state.getConnection();
  }

  public void setConnection(@NonNull SmbConnection connection) {
    state.setConnection(connection);
  }
}
