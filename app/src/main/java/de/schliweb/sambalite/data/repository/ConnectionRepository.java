package de.schliweb.sambalite.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.sambalite.data.model.SmbConnection;
import java.util.List;

/** Repository interface for managing saved SMB connections. */
public interface ConnectionRepository {

  /**
   * Saves a connection to the repository.
   *
   * @param connection The connection to save
   * @return The saved connection with a generated ID (if it was new)
   */
  @Nullable
  SmbConnection saveConnection(@NonNull SmbConnection connection);

  /**
   * Gets all saved connections.
   *
   * @return A list of all saved connections
   */
  @NonNull
  List<SmbConnection> getAllConnections();

  /**
   * Deletes a connection from the repository.
   *
   * @param id The ID of the connection to delete
   * @return true if the connection was deleted, false otherwise
   */
  boolean deleteConnection(@NonNull String id);
}
