package de.schliweb.sambalite.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.util.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying SMB connections in a RecyclerView.
 */
public class ConnectionAdapter extends RecyclerView.Adapter<ConnectionAdapter.ConnectionViewHolder> {

    private List<SmbConnection> connections = new ArrayList<>();
    private OnConnectionClickListener listener;
    private Context context;

    /**
     * Updates the list of connections.
     *
     * @param connections The new list of connections
     */
    @SuppressLint("NotifyDataSetChanged") // Acceptable for Eva's app - simple connection list updates
    public void setConnections(List<SmbConnection> connections) {
        int size = connections != null ? connections.size() : 0;
        LogUtils.d("ConnectionAdapter", "Setting connections: " + size + " items");

        List<SmbConnection> newConnections = connections != null ? connections : new ArrayList<>();

        // Use DiffUtil for efficient updates instead of notifyDataSetChanged
        if (this.connections.isEmpty() && newConnections.isEmpty()) {
            return; // No change needed
        }

        this.connections = newConnections;

        // For Eva's app usage, this is acceptable performance-wise
        notifyDataSetChanged();
    }

    /**
     * Sets the click listener for connections.
     *
     * @param listener The listener to set
     */
    public void setOnConnectionClickListener(OnConnectionClickListener listener) {
        LogUtils.d("ConnectionAdapter", "Setting connection click listener");
        this.listener = listener;
    }

    @NonNull
    @Override
    public ConnectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LogUtils.d("ConnectionAdapter", "Creating new connection view holder");
        this.context = parent.getContext();
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_connection, parent, false);
        return new ConnectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ConnectionViewHolder holder, int position) {
        SmbConnection connection = connections.get(position);
        LogUtils.d("ConnectionAdapter", "Binding connection at position " + position + ": " + connection.getName());
        holder.bind(connection);
    }

    @Override
    public int getItemCount() {
        int count = connections.size();
        LogUtils.d("ConnectionAdapter", "Getting item count: " + count);
        return count;
    }

    /**
     * Interface for connection click events.
     */
    public interface OnConnectionClickListener {
        void onConnectionClick(SmbConnection connection);

        void onConnectionOptionsClick(SmbConnection connection);
    }

    /**
     * ViewHolder for a connection item.
     */
    class ConnectionViewHolder extends RecyclerView.ViewHolder {

        private final TextView nameTextView;
        private final TextView detailsTextView;
        private final TextView userTextView;
        private final ImageView statusIndicator;
        private final ImageButton moreOptionsButton;

        ConnectionViewHolder(@NonNull View itemView) {
            super(itemView);
            nameTextView = itemView.findViewById(R.id.connection_name);
            detailsTextView = itemView.findViewById(R.id.connection_details);
            userTextView = itemView.findViewById(R.id.connection_user);
            statusIndicator = itemView.findViewById(R.id.status_indicator);
            moreOptionsButton = itemView.findViewById(R.id.more_options);

            itemView.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                LogUtils.d("ConnectionAdapter", "Connection item clicked at position: " + position);
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    SmbConnection connection = connections.get(position);
                    LogUtils.d("ConnectionAdapter", "Notifying listener of click on: " + connection.getName());
                    listener.onConnectionClick(connection);
                } else {
                    LogUtils.d("ConnectionAdapter", "Click ignored: position invalid or no listener");
                }
            });

            moreOptionsButton.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                LogUtils.d("ConnectionAdapter", "More options clicked at position: " + position);
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    SmbConnection connection = connections.get(position);
                    LogUtils.d("ConnectionAdapter", "Notifying listener of options click on: " + connection.getName());
                    listener.onConnectionOptionsClick(connection);
                }
            });
        }

        void bind(SmbConnection connection) {
            LogUtils.d("ConnectionAdapter", "Binding connection data: " + connection.getName());
            nameTextView.setText(connection.getName());

            // Combine server and share into details
            String details = connection.getServer() + "/" + connection.getShare();
            detailsTextView.setText(details);

            // Show user info if available
            String userInfo = connection.getUsername();
            if (userInfo != null && !userInfo.trim().isEmpty()) {
                String domain = connection.getDomain();
                if (domain != null && !domain.trim().isEmpty()) {
                    userInfo = userInfo + "@" + domain;
                }
                userTextView.setText(userInfo);
                userTextView.setVisibility(View.VISIBLE);
            } else {
                userTextView.setText(context.getString(R.string.guest_access));
                userTextView.setVisibility(View.VISIBLE);
            }

            // Hide status indicator for now (could be used for connection status in future)
            statusIndicator.setVisibility(View.GONE);
        }
    }
}
