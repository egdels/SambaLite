package de.schliweb.sambalite.ui.adapters;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.NetworkScanner.DiscoveredServer;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying discovered SMB servers in the network scan dialog.
 */
public class DiscoveredServerAdapter extends RecyclerView.Adapter<DiscoveredServerAdapter.ServerViewHolder> {

    private static final String TAG = "DiscoveredServerAdapter";

    private List<DiscoveredServer> servers = new ArrayList<>();
    @Getter
    private DiscoveredServer selectedServer = null;
    private OnServerSelectedListener listener;

    public void setOnServerSelectedListener(OnServerSelectedListener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged") // Acceptable for Eva's app - server discovery updates
    public void setServers(List<DiscoveredServer> servers) {
        this.servers = new ArrayList<>(servers);
        notifyDataSetChanged();
        LogUtils.d(TAG, "Updated server list with " + servers.size() + " servers");
    }

    public void addServer(DiscoveredServer server) {
        servers.add(server);
        int position = servers.size() - 1;
        notifyItemInserted(position);
        LogUtils.d(TAG, "Added server: " + server.getDisplayName() + " at position " + position + ". Total servers: " + servers.size());
    }

    @NonNull
    @Override
    public ServerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LogUtils.d(TAG, "onCreateViewHolder called");
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_discovered_server, parent, false);
        return new ServerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ServerViewHolder holder, int position) {
        DiscoveredServer server = servers.get(position);
        LogUtils.d(TAG, "onBindViewHolder called for position " + position + ", server: " + server.getDisplayName());
        holder.bind(server);
    }

    @Override
    public int getItemCount() {
        return servers.size();
    }

    public interface OnServerSelectedListener {
        void onServerSelected(DiscoveredServer server);
    }

    class ServerViewHolder extends RecyclerView.ViewHolder {
        private final TextView serverName;
        private final TextView serverDetails;
        private final ImageView selectionIndicator;
        private final View itemView;

        public ServerViewHolder(@NonNull View itemView) {
            super(itemView);
            this.itemView = itemView;
            serverName = itemView.findViewById(R.id.server_name);
            serverDetails = itemView.findViewById(R.id.server_details);
            selectionIndicator = itemView.findViewById(R.id.selection_indicator);

            itemView.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;
                if (position != RecyclerView.NO_POSITION) {
                    DiscoveredServer server = servers.get(position);
                    selectServer(server);
                }
            });
        }

        public void bind(DiscoveredServer server) {
            // Set server name (hostname or IP)
            serverName.setText(server.getDisplayName());

            // Build details string
            StringBuilder details = new StringBuilder();

            // Port status
            if (server.isSmbPortOpen()) {
                details.append("SMB: ✓ ");
            } else {
                details.append("SMB: ✗ ");
            }

            if (server.isNetbiosPortOpen()) {
                details.append("NetBIOS: ✓ ");
            } else {
                details.append("NetBIOS: ✗ ");
            }

            // Response time
            details.append("(").append(server.getResponseTime()).append("ms)");

            serverDetails.setText(details.toString());

            // Selection indicator
            boolean isSelected = server.equals(selectedServer);
            selectionIndicator.setVisibility(isSelected ? View.VISIBLE : View.GONE);

            // Update card appearance for selection
            itemView.setSelected(isSelected);
        }

        private void selectServer(DiscoveredServer server) {
            DiscoveredServer previousSelection = selectedServer;
            selectedServer = server;

            // Update UI for previous selection
            if (previousSelection != null) {
                int previousIndex = servers.indexOf(previousSelection);
                if (previousIndex != -1) {
                    notifyItemChanged(previousIndex);
                }
            }

            // Update UI for new selection
            int newIndex = servers.indexOf(server);
            if (newIndex != -1) {
                notifyItemChanged(newIndex);
            }

            // Notify listener
            if (listener != null) {
                listener.onServerSelected(server);
            }

            LogUtils.d(TAG, "Selected server: " + server.getDisplayName());
        }
    }
}
