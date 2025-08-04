package de.schliweb.sambalite.ui;

import android.content.Context;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;

import javax.inject.Inject;

public class ShareReceiverViewModel extends FileOperationsViewModel {

    private final FileBrowserState state;

    @Inject
    public ShareReceiverViewModel(SmbRepository smbRepository, Context context, FileBrowserState state, FileListViewModel fileListViewModel) {
        super(smbRepository, context, state, fileListViewModel);
        this.state = state;
    }

    /**
     * Gets the current connection being used for browsing.
     *
     * @return The current SmbConnection or null if none is set
     */
    public SmbConnection getCurrentConnection() {
        return state.getConnection();
    }

    public void setConnection(SmbConnection connection) {
        state.setConnection(connection);
    }
}
