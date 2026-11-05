/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.monitor;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.sync.SyncActionLog;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncErrorCategory;
import de.schliweb.sambalite.sync.SyncRepository;
import de.schliweb.sambalite.sync.SyncRunLog;
import de.schliweb.sambalite.sync.db.FileSyncState;
import de.schliweb.sambalite.sync.db.SyncStateStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The log of the last run of one sync, or of all syncs, with a search by name or path (#50): "why
 * was file X not synced?". Skipped files are hidden unless asked for or searched for.
 */
public class SyncLogActivity extends AppCompatActivity {

  private static final String EXTRA_CONFIG_ID = "config_id";
  private static final long SEARCH_DELAY_MS = 300;
  private static final int TRACKED_LIMIT = 50;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Adapter adapter = new Adapter();

  @Nullable private String configId;
  private final List<SyncConfig> configs = new ArrayList<>();
  @Nullable private volatile List<SyncLogSearch.Source> sources;
  private TextView resultCount;
  private MaterialSwitch showSkipped;
  private TextInputEditText input;
  private final Runnable searchRunnable = this::search;
  private int generation;

  /** Opens the log of one sync, or of all syncs if {@code configId} is null. */
  @NonNull
  public static Intent createIntent(@NonNull Context context, @Nullable String configId) {
    Intent intent = new Intent(context, SyncLogActivity.class);
    if (configId != null) intent.putExtra(EXTRA_CONFIG_ID, configId);
    return intent;
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    EdgeToEdge.enable(this);
    setContentView(R.layout.activity_sync_log);
    configId = getIntent().getStringExtra(EXTRA_CONFIG_ID);

    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
    de.schliweb.sambalite.ui.utils.UIHelper.applyHorizontalSystemInsets(toolbar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    for (SyncConfig c : new SyncRepository(this).getAllSyncConfigs()) {
      if (configId == null || configId.equals(c.getId())) configs.add(c);
    }
    if (getSupportActionBar() != null) {
      getSupportActionBar()
          .setTitle(
              configId != null && !configs.isEmpty()
                  ? getString(
                      R.string.sync_log_title_config, SyncStatusTexts.configName(configs.get(0)))
                  : getString(R.string.sync_log_title_search));
    }

    RecyclerView list = findViewById(R.id.results);
    list.setLayoutManager(new LinearLayoutManager(this));
    list.setAdapter(adapter);
    ViewCompat.setOnApplyWindowInsetsListener(
        list,
        (v, windowInsets) -> {
          Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
          v.setPadding(
              v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom + 16);
          return windowInsets;
        });

    resultCount = findViewById(R.id.result_count);
    showSkipped = findViewById(R.id.show_skipped_switch);
    showSkipped.setOnCheckedChangeListener((b, checked) -> search());
    input = findViewById(R.id.search_input);
    input.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {}

          @Override
          public void afterTextChanged(Editable s) {
            handler.removeCallbacks(searchRunnable);
            handler.postDelayed(searchRunnable, SEARCH_DELAY_MS);
          }
        });
    if (configId == null) {
      // Opened as "why was a file not synced?": start with the search field
      input.requestFocus();
    }

    resultCount.setText(R.string.loading_system_information);
    executor.execute(
        () -> {
          List<SyncLogSearch.Source> loaded = new ArrayList<>();
          for (SyncConfig c : configs) {
            loaded.add(
                new SyncLogSearch.Source(
                    SyncStatusTexts.configName(c), SyncRunLog.read(this, c.getId())));
          }
          sources = loaded;
          runOnUiThread(this::search);
        });
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    handler.removeCallbacks(searchRunnable);
    executor.shutdownNow();
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      getOnBackPressedDispatcher().onBackPressed();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void search() {
    List<SyncLogSearch.Source> loaded = sources;
    if (loaded == null || isFinishing()) return;
    String query = input.getText() != null ? input.getText().toString().trim() : "";
    boolean skipped = showSkipped.isChecked();
    showSkipped.setEnabled(query.isEmpty());
    int gen = ++generation;
    executor.execute(
        () -> {
          List<SyncLogSearch.Tracked> tracked = new ArrayList<>();
          if (!query.isEmpty()) {
            SyncStateStore store = new SyncStateStore(this);
            for (SyncConfig c : configs) {
              String root = c.getLocalFolderUri();
              if (root == null || root.isEmpty()) continue;
              for (FileSyncState s : store.searchByPath(root, query, TRACKED_LIMIT)) {
                tracked.add(
                    new SyncLogSearch.Tracked(
                        SyncStatusTexts.configName(c), s.relativePath, s.syncedAt));
              }
            }
          }
          List<SyncLogSearch.Result> results =
              SyncLogSearch.search(loaded, tracked, query, skipped);
          runOnUiThread(
              () -> {
                if (gen != generation || isFinishing()) return;
                adapter.setResults(results);
                if (results.isEmpty()) {
                  resultCount.setText(
                      query.isEmpty()
                          ? getString(R.string.sync_log_empty)
                          : getString(R.string.sync_log_no_match, query));
                } else {
                  resultCount.setText(
                      getResources()
                          .getQuantityString(
                              R.plurals.sync_log_results, results.size(), results.size()));
                }
              });
        });
  }

  private final class Adapter extends RecyclerView.Adapter<Holder> {
    private final List<SyncLogSearch.Result> results = new ArrayList<>();
    private final long now = System.currentTimeMillis();

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    void setResults(@NonNull List<SyncLogSearch.Result> newResults) {
      results.clear();
      results.addAll(newResults);
      notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      return new Holder(
          LayoutInflater.from(parent.getContext())
              .inflate(R.layout.item_sync_log_entry, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
      holder.bind(results.get(position), System.currentTimeMillis());
    }

    @Override
    public int getItemCount() {
      return results.size();
    }
  }

  private final class Holder extends RecyclerView.ViewHolder {
    private final View dot;
    private final TextView path;
    private final TextView decision;
    private final TextView detail;

    Holder(@NonNull View itemView) {
      super(itemView);
      dot = itemView.findViewById(R.id.entry_dot);
      path = itemView.findViewById(R.id.entry_path);
      decision = itemView.findViewById(R.id.entry_decision);
      detail = itemView.findViewById(R.id.entry_detail);
    }

    void bind(@NonNull SyncLogSearch.Result r, long now) {
      Context context = itemView.getContext();
      path.setText(r.path);
      String when = SyncStatusTexts.relative(context, r.time, now);
      int color;
      String decisionText;
      String detailText;
      if (r.action == null) {
        color = R.color.sync_status_neutral_dot;
        decisionText = context.getString(R.string.sync_log_tracked);
        detailText = context.getString(R.string.sync_log_tracked_detail, r.configName, when);
      } else {
        color = dotColor(r.action);
        String label = context.getString(SyncStatusTexts.actionLabel(r.action));
        String reason = r.detail.isEmpty() ? "" : SyncStatusTexts.detail(context, r.detail);
        if (r.action == SyncActionLog.Action.ERROR) {
          SyncErrorCategory category =
              r.detail.startsWith("folder skipped")
                  ? SyncErrorCategory.FOLDER_SKIPPED
                  : SyncErrorCategory.classify(new Exception(r.detail), lastSegment(r.path));
          decisionText = label + " · " + SyncStatusTexts.categoryTitle(context, category);
        } else if (r.action == SyncActionLog.Action.SKIPPED && !reason.isEmpty()) {
          decisionText = label + " · " + reason;
          reason = "";
        } else {
          decisionText = label;
        }
        detailText = r.configName + " · " + when + (reason.isEmpty() ? "" : " · " + reason);
      }
      decision.setText(decisionText);
      detail.setText(detailText);
      dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, color)));
    }
  }

  private static int dotColor(@NonNull SyncActionLog.Action action) {
    switch (action) {
      case ERROR:
      case ABORTED_LOW_SPACE:
        return R.color.sync_status_error_dot;
      case TIMESTAMP_FAILED:
      case DISCARDED:
      case MIRROR_ABORTED:
        return R.color.sync_status_warning_dot;
      case SKIPPED:
        return R.color.sync_status_neutral_dot;
      case UPLOADED:
      case DOWNLOADED:
        return R.color.sync_status_ok_dot;
      default:
        return R.color.sync_status_running_dot;
    }
  }

  @NonNull
  private static String lastSegment(@NonNull String path) {
    int i = path.lastIndexOf('/');
    return i >= 0 ? path.substring(i + 1) : path;
  }
}
