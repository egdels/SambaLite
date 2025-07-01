package de.schliweb.sambalite.ui;

import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying SMB files and directories in a RecyclerView.
 */
public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    @Getter
    private List<SmbFileItem> files = new ArrayList<>();
    private OnFileClickListener listener;
    private OnFileLongClickListener longClickListener;
    private boolean showParentDirectory = false;

    /**
     * Updates the list of files.
     *
     * @param files The new list of files
     */
    public void setFiles(List<SmbFileItem> files) {
        int size = files != null ? files.size() : 0;
        LogUtils.d("FileAdapter", "Setting files: " + size + " items");
        this.files = files != null ? files : new ArrayList<>();
        notifyDataSetChanged();
    }

    /**
     * Gets whether to show a parent directory item at the top of the list.
     *
     * @return True if showing parent directory, false otherwise
     */
    public boolean getShowParentDirectory() {
        return showParentDirectory;
    }

    /**
     * Sets whether to show a parent directory item at the top of the list.
     *
     * @param showParentDirectory True to show parent directory, false otherwise
     */
    public void setShowParentDirectory(boolean showParentDirectory) {
        LogUtils.d("FileAdapter", "Setting showParentDirectory: " + showParentDirectory);
        this.showParentDirectory = showParentDirectory;
        notifyDataSetChanged();
    }

    /**
     * Sets the click listener for files.
     *
     * @param listener The listener to set
     */
    public void setOnFileClickListener(OnFileClickListener listener) {
        LogUtils.d("FileAdapter", "Setting file click listener");
        this.listener = listener;
    }

    /**
     * Sets the long click listener for files.
     *
     * @param listener The listener to set
     */
    public void setOnFileLongClickListener(OnFileLongClickListener listener) {
        LogUtils.d("FileAdapter", "Setting file long click listener");
        this.longClickListener = listener;
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LogUtils.d("FileAdapter", "Creating new file view holder");
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        LogUtils.d("FileAdapter", "Binding file at position: " + position);
        if (showParentDirectory && position == 0) {
            // Parent directory item
            LogUtils.d("FileAdapter", "Binding parent directory item");
            holder.bind(null);
        } else {
            // Regular file or directory
            int filePosition = showParentDirectory ? position - 1 : position;
            SmbFileItem file = files.get(filePosition);
            LogUtils.d("FileAdapter", "Binding file at adjusted position " + filePosition + ": " + file.getName() + ", isDirectory: " + file.isDirectory());
            holder.bind(file);
        }
    }

    @Override
    public int getItemCount() {
        int count = files.size() + (showParentDirectory ? 1 : 0);
        LogUtils.d("FileAdapter", "Getting item count: " + count + " (files: " + files.size() + ", showParentDirectory: " + showParentDirectory + ")");
        return count;
    }

    /**
     * Interface for file click events.
     */
    public interface OnFileClickListener {
        void onFileClick(SmbFileItem file);

        void onParentDirectoryClick();
    }

    /**
     * Interface for file long click events.
     */
    public interface OnFileLongClickListener {
        boolean onFileLongClick(SmbFileItem file);

        boolean onParentDirectoryLongClick();
    }

    /**
     * ViewHolder for a file item.
     */
    class FileViewHolder extends RecyclerView.ViewHolder {

        private final ImageView iconView;
        private final TextView nameView;
        private final TextView dateView;
        private final TextView sizeView;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.file_icon);
            nameView = itemView.findViewById(R.id.file_name);
            dateView = itemView.findViewById(R.id.file_date);
            sizeView = itemView.findViewById(R.id.file_size);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                LogUtils.d("FileAdapter", "File item clicked at position: " + position);
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    if (showParentDirectory && position == 0) {
                        // Parent directory clicked
                        LogUtils.d("FileAdapter", "Parent directory clicked, notifying listener");
                        listener.onParentDirectoryClick();
                    } else {
                        // Regular file or directory clicked
                        int filePosition = showParentDirectory ? position - 1 : position;
                        SmbFileItem file = files.get(filePosition);
                        LogUtils.d("FileAdapter", "File clicked at adjusted position " + filePosition + ": " + file.getName() + ", isDirectory: " + file.isDirectory());
                        listener.onFileClick(file);
                    }
                } else {
                    LogUtils.d("FileAdapter", "Click ignored: position invalid or no listener");
                }
            });

            itemView.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                LogUtils.d("FileAdapter", "File item long clicked at position: " + position);
                if (position != RecyclerView.NO_POSITION && longClickListener != null) {
                    if (showParentDirectory && position == 0) {
                        // Parent directory long clicked
                        LogUtils.d("FileAdapter", "Parent directory long clicked, notifying listener");
                        return longClickListener.onParentDirectoryLongClick();
                    } else {
                        // Regular file or directory long clicked
                        int filePosition = showParentDirectory ? position - 1 : position;
                        SmbFileItem file = files.get(filePosition);
                        LogUtils.d("FileAdapter", "File long clicked at adjusted position " + filePosition + ": " + file.getName() + ", isDirectory: " + file.isDirectory());
                        return longClickListener.onFileLongClick(file);
                    }
                } else {
                    LogUtils.d("FileAdapter", "Long click ignored: position invalid or no listener");
                    return false;
                }
            });
        }

        void bind(SmbFileItem file) {
            if (file == null) {
                // Parent directory
                LogUtils.d("FileAdapter", "Binding parent directory item");
                iconView.setImageResource(android.R.drawable.ic_menu_revert);
                nameView.setText(R.string.parent_directory);
                dateView.setText("");
                sizeView.setText("");
                return;
            }

            LogUtils.d("FileAdapter", "Binding file: " + file.getName() + ", isDirectory: " + file.isDirectory());

            // Set icon based on file type
            if (file.isDirectory()) {
                LogUtils.d("FileAdapter", "Setting directory icon for: " + file.getName());
                iconView.setImageResource(android.R.drawable.ic_menu_more);
            } else {
                LogUtils.d("FileAdapter", "Setting file icon for: " + file.getName());
                iconView.setImageResource(android.R.drawable.ic_menu_save);
            }

            // Set file name
            nameView.setText(file.getName());

            // Set file date
            if (file.getLastModified() != null) {
                String formattedDate = DateFormat.format("MMM dd, yyyy", file.getLastModified()).toString();
                LogUtils.d("FileAdapter", "Setting date for " + file.getName() + ": " + formattedDate);
                dateView.setText(formattedDate);
            } else {
                LogUtils.d("FileAdapter", "No date available for: " + file.getName());
                dateView.setText("");
            }

            // Set file size (only for files, not directories)
            if (file.isFile()) {
                String formattedSize = Formatter.formatFileSize(itemView.getContext(), file.getSize());
                LogUtils.d("FileAdapter", "Setting size for " + file.getName() + ": " + formattedSize);
                sizeView.setText(formattedSize);
            } else {
                LogUtils.d("FileAdapter", "No size for directory: " + file.getName());
                sizeView.setText("");
            }
        }
    }
}
