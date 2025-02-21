package com.termux.x11;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class VirtualKeyAdapter extends RecyclerView.Adapter<VirtualKeyAdapter.ViewHolder> {

    private List<VirtualKeyModel> keyList;
    private OnKeyDeleteListener deleteListener;

    public interface OnKeyDeleteListener {
        void onDelete(int position);
    }

    public VirtualKeyAdapter(List<VirtualKeyModel> keyList, OnKeyDeleteListener deleteListener) {
        this.keyList = keyList;
        this.deleteListener = deleteListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_virtual_key, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        VirtualKeyModel key = keyList.get(position);
        holder.keyNameTextView.setText(key.getKeyName());

        // Șterge tasta la apăsarea butonului
        holder.deleteKeyButton.setOnClickListener(v -> deleteListener.onDelete(position));
    }

    @Override
    public int getItemCount() {
        return keyList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView keyNameTextView;
        Button deleteKeyButton;

        ViewHolder(View itemView) {
            super(itemView);
            keyNameTextView = itemView.findViewById(R.id.keyNameTextView);
            deleteKeyButton = itemView.findViewById(R.id.deleteKeyButton);
        }
    }
}
