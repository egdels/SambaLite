package de.schliweb.sambalite.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.util.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying available SMB shares in the connection dialog.
 */
public class SharesAdapter extends RecyclerView.Adapter<SharesAdapter.ShareViewHolder> {

    private static final String TAG = "SharesAdapter";

    private List<String> shares = new ArrayList<>();
    private String selectedShare = null;
    private OnShareSelectedListener listener;

    public void setOnShareSelectedListener(OnShareSelectedListener listener) {
        this.listener = listener;
    }

    public void setShares(List<String> shares) {
        this.shares = new ArrayList<>(shares);
        this.selectedShare = null; // Reset selection
        notifyDataSetChanged();
        LogUtils.d(TAG, "Updated shares list with " + shares.size() + " shares");
    }

    public void clearShares() {
        this.shares.clear();
        this.selectedShare = null;
        notifyDataSetChanged();
        LogUtils.d(TAG, "Cleared shares list");
    }

    @NonNull
    @Override
    public ShareViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_share, parent, false);
        return new ShareViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ShareViewHolder holder, int position) {
        String share = shares.get(position);
        holder.bind(share, share.equals(selectedShare));
    }

    @Override
    public int getItemCount() {
        return shares.size();
    }

    public interface OnShareSelectedListener {
        void onShareSelected(String shareName);
    }

    class ShareViewHolder extends RecyclerView.ViewHolder {
        private final TextView shareName;
        private final View itemView;

        public ShareViewHolder(@NonNull View itemView) {
            super(itemView);
            this.itemView = itemView;
            shareName = itemView.findViewById(R.id.share_name);

            itemView.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;
                if (position != RecyclerView.NO_POSITION) {
                    String share = shares.get(position);
                    selectShare(share);
                }
            });
        }

        public void bind(String share, boolean isSelected) {
            shareName.setText(share);
            itemView.setSelected(isSelected);

            // Update visual state based on selection
            if (isSelected) {
                // Use a simple highlight background
                itemView.setBackgroundColor(0xFF2196F3); // Material Blue
                shareName.setTextColor(android.graphics.Color.WHITE);
            } else {
                itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                // Use theme-appropriate text color
                android.content.res.TypedArray typedArray = itemView.getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
                int textColor = typedArray.getColor(0, android.graphics.Color.BLACK);
                typedArray.recycle();
                shareName.setTextColor(textColor);
            }
        }

        private void selectShare(String share) {
            String previousSelection = selectedShare;
            selectedShare = share;

            // Update UI for previous selection
            if (previousSelection != null) {
                int previousIndex = shares.indexOf(previousSelection);
                if (previousIndex != -1) {
                    notifyItemChanged(previousIndex);
                }
            }

            // Update UI for new selection
            int newIndex = shares.indexOf(share);
            if (newIndex != -1) {
                notifyItemChanged(newIndex);
            }

            // Notify listener
            if (listener != null) {
                listener.onShareSelected(share);
            }

            LogUtils.d(TAG, "Selected share: " + share);
        }
    }
}
