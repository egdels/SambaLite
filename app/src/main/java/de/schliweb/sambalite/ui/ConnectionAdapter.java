package de.schliweb.sambalite.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    /**
     * Updates the list of connections.
     *
     * @param connections The new list of connections
     */
    public void setConnections(List<SmbConnection> connections) {
        int size = connections != null ? connections.size() : 0;
        LogUtils.d("ConnectionAdapter", "Setting connections: " + size + " items");
        this.connections = connections != null ? connections : new ArrayList<>();
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

    /**
     * Gets the connection at the specified position.
     *
     * @param position The position of the connection to get
     * @return The connection at the specified position
     */
    public SmbConnection getConnectionAt(int position) {
        LogUtils.d("ConnectionAdapter", "Getting connection at position: " + position);
        return connections.get(position);
    }

    @NonNull
    @Override
    public ConnectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LogUtils.d("ConnectionAdapter", "Creating new connection view holder");
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

        void onConnectionLongClick(SmbConnection connection);
    }

    /**
     * ViewHolder for a connection item.
     */
    class ConnectionViewHolder extends RecyclerView.ViewHolder {

        private final TextView nameTextView;
        private final TextView serverTextView;
        private final TextView shareTextView;

        ConnectionViewHolder(@NonNull View itemView) {
            super(itemView);
            nameTextView = itemView.findViewById(R.id.connection_name);
            serverTextView = itemView.findViewById(R.id.connection_server);
            shareTextView = itemView.findViewById(R.id.connection_share);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                LogUtils.d("ConnectionAdapter", "Connection item clicked at position: " + position);
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    SmbConnection connection = connections.get(position);
                    LogUtils.d("ConnectionAdapter", "Notifying listener of click on: " + connection.getName());
                    listener.onConnectionClick(connection);
                } else {
                    LogUtils.d("ConnectionAdapter", "Click ignored: position invalid or no listener");
                }
            });

            itemView.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                LogUtils.d("ConnectionAdapter", "Connection item long-clicked at position: " + position);
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    SmbConnection connection = connections.get(position);
                    LogUtils.d("ConnectionAdapter", "Notifying listener of long-click on: " + connection.getName());
                    listener.onConnectionLongClick(connection);
                    return true;
                }
                LogUtils.d("ConnectionAdapter", "Long-click ignored: position invalid or no listener");
                return false;
            });
        }

        void bind(SmbConnection connection) {
            LogUtils.d("ConnectionAdapter", "Binding connection data: " + connection.getName());
            nameTextView.setText(connection.getName());
            serverTextView.setText(connection.getServer());
            shareTextView.setText(connection.getShare());
        }
    }
}
