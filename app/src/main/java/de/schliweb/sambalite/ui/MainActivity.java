package de.schliweb.sambalite.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.ui.adapters.DiscoveredServerAdapter;
import de.schliweb.sambalite.ui.adapters.SharesAdapter;
import de.schliweb.sambalite.ui.utils.LoadingIndicator;
import de.schliweb.sambalite.util.*;

import javax.inject.Inject;

/**
 * Main entry point for the SambaLite app.
 * Displays a list of saved SMB connections and allows adding new ones.
 */
public class MainActivity extends AppCompatActivity implements ConnectionAdapter.OnConnectionClickListener {

    @Inject
    ViewModelProvider.Factory viewModelFactory;

    private MainViewModel viewModel;
    private ConnectionAdapter adapter;
    private LoadingIndicator loadingIndicator;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fab;
    private NetworkScanner networkScanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LogUtils.d("MainActivity", "onCreate called");

        // Initialize loading indicator
        loadingIndicator = new LoadingIndicator(this);
        LogUtils.d("MainActivity", "Loading indicator initialized");

        // Initialize network scanner
        networkScanner = new NetworkScanner(this);
        LogUtils.d("MainActivity", "Network scanner initialized");

        // Get the Dagger component and inject dependencies
        AppComponent appComponent = ((SambaLiteApp) getApplication()).getAppComponent();
        appComponent.inject(this);
        LogUtils.d("MainActivity", "Dependencies injected");

        super.onCreate(savedInstanceState);

        // Configure edge-to-edge display for better landscape experience
        Window window = getWindow();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern API (Android 11+)
            WindowCompat.setDecorFitsSystemWindows(window, false);
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        } else {
            // Legacy API (Android 10 and below)
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            window.getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        setContentView(R.layout.activity_main);
        LogUtils.d("MainActivity", "Content view set");

        // Set up the toolbar as action bar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            LogUtils.d("MainActivity", "Toolbar set as action bar");
        } else {
            LogUtils.w("MainActivity", "Toolbar not found in layout");
        }

        // Initialize ViewModel
        viewModel = new ViewModelProvider(this, viewModelFactory).get(MainViewModel.class);
        LogUtils.d("MainActivity", "ViewModel initialized");

        // Set up RecyclerView
        RecyclerView recyclerView = findViewById(R.id.connections_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ConnectionAdapter();
        adapter.setOnConnectionClickListener(this);
        recyclerView.setAdapter(adapter);
        LogUtils.d("MainActivity", "RecyclerView and adapter set up");

        // Long click functionality is used for connection options including delete
        LogUtils.d("MainActivity", "Long click functionality for connection options is already set up");

        // Set up FAB for adding new connections with enhanced animation
        fab = findViewById(R.id.fab_add_connection);
        if (fab != null) {
            fab.setOnClickListener(v -> {
                EnhancedUIUtils.scaleUp(v);
                showAddConnectionDialog();
            });
            EnhancedUIUtils.addRippleEffect(fab);
            LogUtils.d("MainActivity", "FAB set up with enhanced animations");
        } else {
            LogUtils.w("MainActivity", "FAB not found in layout");
        }

        // Set up welcome card button
        com.google.android.material.button.MaterialButton welcomeAddButton = findViewById(R.id.welcome_add_button);
        if (welcomeAddButton != null) {
            welcomeAddButton.setOnClickListener(v -> showAddConnectionDialog());
        }

        // Get UI elements for empty state management
        View welcomeCard = findViewById(R.id.welcome_card);
        View connectionsHeader = findViewById(R.id.connections_header);

        // Observe connections
        viewModel.getConnections().observe(this, connections -> {
            LogUtils.d("MainActivity", "Connections updated: " + connections.size() + " connections");
            LogUtils.d("MainActivity", "Connections list: " + (connections != null ? connections.toString() : "null"));
            adapter.setConnections(connections);

            // Manage empty state visibility
            if (connections == null || connections.isEmpty()) {
                LogUtils.d("MainActivity", "No connections available - showing welcome card");
                if (welcomeCard != null) welcomeCard.setVisibility(View.VISIBLE);
                if (connectionsHeader != null) connectionsHeader.setVisibility(View.GONE);
            } else {
                LogUtils.d("MainActivity", "Connections available - hiding welcome card");
                if (welcomeCard != null) welcomeCard.setVisibility(View.GONE);
                if (connectionsHeader != null) connectionsHeader.setVisibility(View.VISIBLE);
            }
        });

        // Observe error messages with enhanced UI feedback
        viewModel.getErrorMessage().observe(this, errorMessage -> {
            if (errorMessage != null && !errorMessage.isEmpty()) {
                LogUtils.w("MainActivity", "Error message received: " + errorMessage);
                EnhancedUIUtils.showError(this, errorMessage);
            }
        });

        // Observe loading state with unified loading indicator
        viewModel.isLoading().observe(this, isLoading -> {
            LogUtils.d("MainActivity", "Loading state changed: " + isLoading);
            if (isLoading) {
                loadingIndicator.show(R.string.loading_files);
            } else {
                loadingIndicator.hide();
            }
        });

        // Log performance metrics

        LogUtils.i("MainActivity", "Memory: " + SimplePerformanceMonitor.getMemoryInfo());

        // Force trigger the initial connections load for debugging
        LogUtils.d("MainActivity", "Triggering initial connections load");
        viewModel.loadConnections();

        // Battery optimization check for better background performance
        checkBatteryOptimizationOnFirstRun();

        // Debug: Check if there are already connections loaded
        if (viewModel.getConnections().getValue() != null) {
            LogUtils.d("MainActivity", "ViewModel already has connections: " + viewModel.getConnections().getValue().size());
        } else {
            LogUtils.d("MainActivity", "ViewModel connections are null at startup");
        }
    }

    @Override
    public void onConnectionClick(SmbConnection connection) {
        LogUtils.d("MainActivity", "Connection clicked: " + connection.getName());

        // Open file browser activity for this connection
        Intent intent = FileBrowserActivity.createIntent(this, connection.getId());
        startActivity(intent);

        EnhancedUIUtils.showInfo(this, "Opening " + connection.getName());
        LogUtils.i("MainActivity", "Opening RefactoredFileBrowserActivity for connection: " + connection.getName());
    }

    @Override
    public void onConnectionOptionsClick(SmbConnection connection) {
        LogUtils.d("MainActivity", "Connection options clicked: " + connection.getName());
        showConnectionOptionsDialog(connection);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View v = getCurrentFocus();
            if (v instanceof com.google.android.material.textfield.TextInputEditText) {
                LogUtils.d("MainActivity", "Touch event on TextInputEditText");
                // Check if the touch was outside the focused text field
                float x = event.getRawX();
                float y = event.getRawY();
                int[] location = new int[2];
                v.getLocationOnScreen(location);

                if (x < location[0] || x > location[0] + v.getWidth() || y < location[1] || y > location[1] + v.getHeight()) {
                    LogUtils.d("MainActivity", "Touch outside of text field, hiding keyboard");
                    // Touch was outside the text field, hide keyboard
                    KeyboardUtils.hideKeyboard(this);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * Shows a dialog for adding a new connection.
     */
    private void showAddConnectionDialog() {
        LogUtils.d("MainActivity", "Showing add connection dialog");
        // Inflate the dialog layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_connection, null);

        // Get references to the input fields
        com.google.android.material.textfield.TextInputLayout nameLayout = dialogView.findViewById(R.id.name_layout);
        com.google.android.material.textfield.TextInputLayout serverLayout = dialogView.findViewById(R.id.server_layout);
        com.google.android.material.textfield.TextInputLayout shareLayout = dialogView.findViewById(R.id.share_layout);
        com.google.android.material.textfield.TextInputEditText nameEditText = dialogView.findViewById(R.id.name_edit_text);
        com.google.android.material.textfield.TextInputEditText serverEditText = dialogView.findViewById(R.id.server_edit_text);
        com.google.android.material.textfield.TextInputEditText shareEditText = dialogView.findViewById(R.id.share_edit_text);
        com.google.android.material.textfield.TextInputEditText usernameEditText = dialogView.findViewById(R.id.username_edit_text);
        com.google.android.material.textfield.TextInputEditText passwordEditText = dialogView.findViewById(R.id.password_edit_text);
        com.google.android.material.textfield.TextInputEditText domainEditText = dialogView.findViewById(R.id.domain_edit_text);

        Button testConnectionButton = dialogView.findViewById(R.id.test_connection_button);
        Button scanNetworkButton = dialogView.findViewById(R.id.scan_network_button);

        // Get references to shares UI elements
        View sharesSection = dialogView.findViewById(R.id.shares_section);
        ProgressBar sharesProgress = dialogView.findViewById(R.id.shares_progress);
        RecyclerView sharesRecyclerView = dialogView.findViewById(R.id.shares_recycler_view);
        TextView sharesStatusText = dialogView.findViewById(R.id.shares_status_text);

        // Set up shares RecyclerView
        SharesAdapter sharesAdapter = new SharesAdapter();
        sharesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        sharesRecyclerView.setAdapter(sharesAdapter);

        // Set up share selection listener
        sharesAdapter.setOnShareSelectedListener(shareName -> {
            LogUtils.d("MainActivity", "Share selected: " + shareName);
            shareEditText.setText(shareName);
        });

        // Set up server field text watcher for automatic share discovery
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.atomic.AtomicReference<Runnable> pendingDiscovery = new java.util.concurrent.atomic.AtomicReference<>();

        serverEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Not needed
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Cancel any pending discovery
                Runnable pending = pendingDiscovery.get();
                if (pending != null) {
                    handler.removeCallbacks(pending);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                String serverText = s.toString().trim();

                // Hide shares section if server is empty
                if (serverText.isEmpty()) {
                    sharesSection.setVisibility(View.GONE);
                    return;
                }

                // Only proceed if server looks like a complete IP address or hostname
                if (isValidServerAddress(serverText)) {
                    // Debounce the discovery to avoid excessive network calls
                    Runnable discoveryTask = () -> {
                        discoverShares(serverText, usernameEditText.getText().toString().trim(), passwordEditText.getText().toString().trim(), domainEditText.getText().toString().trim(), sharesSection, sharesProgress, sharesAdapter, sharesStatusText);
                    };

                    pendingDiscovery.set(discoveryTask);
                    handler.postDelayed(discoveryTask, 1500); // Wait 1.5 seconds after user stops typing
                } else {
                    // Hide shares section for incomplete addresses
                    sharesSection.setVisibility(View.GONE);
                }
            }
        });

        // Create the dialog
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.add_new_connection).setView(dialogView).setPositiveButton(R.string.save, null) // Set to null initially to prevent auto-dismiss
                .setNegativeButton(R.string.cancel, (dialogInterface, which) -> {
                    LogUtils.d("MainActivity", "Add connection dialog cancelled");
                    dialogInterface.dismiss();
                }).create();

        // Show the dialog
        dialog.show();

        // Set dialog dismiss listener to hide keyboard
        dialog.setOnDismissListener(dialogInterface -> {
            KeyboardUtils.hideKeyboard(MainActivity.this);
        });

        // Set up the positive button click listener (after dialog is shown to prevent auto-dismiss)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            // Validate input
            LogUtils.d("MainActivity", "Validating connection input");
            boolean isValid = true;

            String name = nameEditText.getText().toString().trim();
            if (name.isEmpty()) {
                LogUtils.d("MainActivity", "Validation failed: name is empty");
                nameLayout.setError(getString(R.string.error_name_required));
                isValid = false;
            } else {
                nameLayout.setError(null);
            }

            String server = serverEditText.getText().toString().trim();
            if (server.isEmpty()) {
                LogUtils.d("MainActivity", "Validation failed: server is empty");
                serverLayout.setError(getString(R.string.error_server_required));
                isValid = false;
            } else {
                serverLayout.setError(null);
            }

            String share = shareEditText.getText().toString().trim();
            if (share.isEmpty()) {
                LogUtils.d("MainActivity", "Validation failed: share is empty");
                shareLayout.setError(getString(R.string.error_share_required));
                isValid = false;
            } else {
                shareLayout.setError(null);
            }

            // If validation passes, save the connection
            if (isValid) {
                LogUtils.d("MainActivity", "Validation passed, creating connection object");
                SmbConnection connection = new SmbConnection();
                connection.setName(name);
                connection.setServer(server);
                connection.setShare(share);
                connection.setUsername(usernameEditText.getText().toString().trim());
                connection.setPassword(passwordEditText.getText().toString().trim());
                connection.setDomain(domainEditText.getText().toString().trim());

                LogUtils.i("MainActivity", "Saving new connection: " + name);
                viewModel.saveConnection(connection);
                dialog.dismiss();
            }
        });

        // Set up the test connection button
        testConnectionButton.setOnClickListener(v -> {
            // Validate input
            LogUtils.d("MainActivity", "Validating connection for testing");
            boolean isValid = true;

            String server = serverEditText.getText().toString().trim();
            if (server.isEmpty()) {
                LogUtils.d("MainActivity", "Test validation failed: server is empty");
                serverLayout.setError(getString(R.string.error_server_required));
                isValid = false;
            } else {
                serverLayout.setError(null);
            }

            String share = shareEditText.getText().toString().trim();
            if (share.isEmpty()) {
                LogUtils.d("MainActivity", "Test validation failed: share is empty");
                shareLayout.setError(getString(R.string.error_share_required));
                isValid = false;
            } else {
                shareLayout.setError(null);
            }

            // If validation passes, test the connection
            if (isValid) {
                LogUtils.d("MainActivity", "Test validation passed, creating test connection object");
                SmbConnection testConnection = new SmbConnection();
                testConnection.setServer(server);
                testConnection.setShare(share);
                testConnection.setUsername(usernameEditText.getText().toString().trim());
                testConnection.setPassword(passwordEditText.getText().toString().trim());
                testConnection.setDomain(domainEditText.getText().toString().trim());

                LogUtils.i("MainActivity", "Testing connection to server: " + server);
                testConnection(testConnection);
            }
        });

        // Set up the scan network button
        scanNetworkButton.setOnClickListener(v -> {
            LogUtils.d("MainActivity", "Scan network button clicked");
            showNetworkScanDialog(serverEditText, nameEditText);
        });
    }

    /**
     * Shows a dialog with options for a connection.
     */
    private void showConnectionOptionsDialog(SmbConnection connection) {
        LogUtils.d("MainActivity", "Showing options dialog for connection: " + connection.getName());
        String[] options = {"Edit", "Test Connection", "Delete"};

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(connection.getName()).setItems(options, (dialog, which) -> {
            switch (which) {
                case 0: // Edit
                    LogUtils.d("MainActivity", "Selected edit option for connection: " + connection.getName());
                    showEditConnectionDialog(connection);
                    break;
                case 1: // Test Connection
                    LogUtils.d("MainActivity", "Selected test option for connection: " + connection.getName());
                    testConnection(connection);
                    break;
                case 2: // Delete
                    LogUtils.d("MainActivity", "Selected delete option for connection: " + connection.getName());
                    confirmDeleteConnection(connection);
                    break;
            }
        }).setNegativeButton(R.string.cancel, (dialog, which) -> {
            LogUtils.d("MainActivity", "Connection options dialog cancelled");
            dialog.dismiss();
        }).show();
    }

    /**
     * Tests a connection and shows the result.
     */
    private void testConnection(SmbConnection connection) {
        LogUtils.d("MainActivity", "Testing connection to: " + connection.getServer() + "/" + connection.getShare());
        viewModel.testConnection(connection, (success, message) -> {
            LogUtils.d("MainActivity", "Connection test result: " + (success ? "success" : "failure") + " - " + message);
            runOnUiThread(() -> {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        });
    }

    /**
     * Shows a dialog for editing an existing connection.
     */
    private void showEditConnectionDialog(SmbConnection connection) {
        LogUtils.d("MainActivity", "Showing edit connection dialog for: " + connection.getName());
        // Inflate the dialog layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_connection, null);

        // Get references to the input fields
        com.google.android.material.textfield.TextInputLayout nameLayout = dialogView.findViewById(R.id.name_layout);
        com.google.android.material.textfield.TextInputLayout serverLayout = dialogView.findViewById(R.id.server_layout);
        com.google.android.material.textfield.TextInputLayout shareLayout = dialogView.findViewById(R.id.share_layout);
        com.google.android.material.textfield.TextInputEditText nameEditText = dialogView.findViewById(R.id.name_edit_text);
        com.google.android.material.textfield.TextInputEditText serverEditText = dialogView.findViewById(R.id.server_edit_text);
        com.google.android.material.textfield.TextInputEditText shareEditText = dialogView.findViewById(R.id.share_edit_text);
        com.google.android.material.textfield.TextInputEditText usernameEditText = dialogView.findViewById(R.id.username_edit_text);
        com.google.android.material.textfield.TextInputEditText passwordEditText = dialogView.findViewById(R.id.password_edit_text);
        com.google.android.material.textfield.TextInputEditText domainEditText = dialogView.findViewById(R.id.domain_edit_text);

        Button testConnectionButton = dialogView.findViewById(R.id.test_connection_button);

        // Get references to shares UI elements
        View sharesSection = dialogView.findViewById(R.id.shares_section);
        ProgressBar sharesProgress = dialogView.findViewById(R.id.shares_progress);
        RecyclerView sharesRecyclerView = dialogView.findViewById(R.id.shares_recycler_view);
        TextView sharesStatusText = dialogView.findViewById(R.id.shares_status_text);

        // Set up shares RecyclerView
        SharesAdapter sharesAdapter = new SharesAdapter();
        sharesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        sharesRecyclerView.setAdapter(sharesAdapter);

        // Set up share selection listener
        sharesAdapter.setOnShareSelectedListener(shareName -> {
            LogUtils.d("MainActivity", "Share selected: " + shareName);
            shareEditText.setText(shareName);
        });

        // Set up server field text watcher for automatic share discovery
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.atomic.AtomicReference<Runnable> pendingDiscovery = new java.util.concurrent.atomic.AtomicReference<>();

        serverEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Not needed
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Cancel any pending discovery
                Runnable pending = pendingDiscovery.get();
                if (pending != null) {
                    handler.removeCallbacks(pending);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                String serverText = s.toString().trim();

                // Hide shares section if server is empty
                if (serverText.isEmpty()) {
                    sharesSection.setVisibility(View.GONE);
                    return;
                }

                // Only proceed if server looks like a complete IP address or hostname
                if (isValidServerAddress(serverText)) {
                    // Debounce the discovery to avoid excessive network calls
                    Runnable discoveryTask = () -> {
                        discoverShares(serverText, usernameEditText.getText().toString().trim(), passwordEditText.getText().toString().trim(), domainEditText.getText().toString().trim(), sharesSection, sharesProgress, sharesAdapter, sharesStatusText);
                    };

                    pendingDiscovery.set(discoveryTask);
                    handler.postDelayed(discoveryTask, 1500); // Wait 1.5 seconds after user stops typing
                } else {
                    // Hide shares section for incomplete addresses
                    sharesSection.setVisibility(View.GONE);
                }
            }
        });

        // Pre-populate fields with existing connection data
        nameEditText.setText(connection.getName());
        serverEditText.setText(connection.getServer());
        shareEditText.setText(connection.getShare());
        usernameEditText.setText(connection.getUsername());
        passwordEditText.setText(connection.getPassword());
        domainEditText.setText(connection.getDomain());

        // Create the dialog
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.edit_connection).setView(dialogView).setPositiveButton(R.string.save, null) // Set to null initially to prevent auto-dismiss
                .setNegativeButton(R.string.cancel, (dialogInterface, which) -> {
                    LogUtils.d("MainActivity", "Edit connection dialog cancelled");
                    dialogInterface.dismiss();
                }).create();

        // Show the dialog
        dialog.show();

        // Set dialog dismiss listener to hide keyboard
        dialog.setOnDismissListener(dialogInterface -> {
            KeyboardUtils.hideKeyboard(MainActivity.this);
        });

        // Set up the positive button click listener (after dialog is shown to prevent auto-dismiss)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            // Validate input
            LogUtils.d("MainActivity", "Validating edited connection input");
            boolean isValid = true;

            String name = nameEditText.getText().toString().trim();
            if (name.isEmpty()) {
                LogUtils.d("MainActivity", "Edit validation failed: name is empty");
                nameLayout.setError(getString(R.string.error_name_required));
                isValid = false;
            } else {
                nameLayout.setError(null);
            }

            String server = serverEditText.getText().toString().trim();
            if (server.isEmpty()) {
                LogUtils.d("MainActivity", "Edit validation failed: server is empty");
                serverLayout.setError(getString(R.string.error_server_required));
                isValid = false;
            } else {
                serverLayout.setError(null);
            }

            String share = shareEditText.getText().toString().trim();
            if (share.isEmpty()) {
                LogUtils.d("MainActivity", "Edit validation failed: share is empty");
                shareLayout.setError(getString(R.string.error_share_required));
                isValid = false;
            } else {
                shareLayout.setError(null);
            }

            // If validation passes, update the connection
            if (isValid) {
                LogUtils.d("MainActivity", "Edit validation passed, updating connection object");
                // Create a new connection with the same ID to update the existing one
                SmbConnection updatedConnection = new SmbConnection();
                updatedConnection.setId(connection.getId());
                updatedConnection.setName(name);
                updatedConnection.setServer(server);
                updatedConnection.setShare(share);
                updatedConnection.setUsername(usernameEditText.getText().toString().trim());
                updatedConnection.setPassword(passwordEditText.getText().toString().trim());
                updatedConnection.setDomain(domainEditText.getText().toString().trim());

                LogUtils.i("MainActivity", "Updating connection: " + name);
                viewModel.saveConnection(updatedConnection);
                dialog.dismiss();
            }
        });

        // Set up the test connection button
        testConnectionButton.setOnClickListener(v -> {
            // Validate input
            LogUtils.d("MainActivity", "Validating connection for testing (edit dialog)");
            boolean isValid = true;

            String server = serverEditText.getText().toString().trim();
            if (server.isEmpty()) {
                LogUtils.d("MainActivity", "Edit test validation failed: server is empty");
                serverLayout.setError(getString(R.string.error_server_required));
                isValid = false;
            } else {
                serverLayout.setError(null);
            }

            String share = shareEditText.getText().toString().trim();
            if (share.isEmpty()) {
                LogUtils.d("MainActivity", "Edit test validation failed: share is empty");
                shareLayout.setError(getString(R.string.error_share_required));
                isValid = false;
            } else {
                shareLayout.setError(null);
            }

            // If validation passes, test the connection
            if (isValid) {
                LogUtils.d("MainActivity", "Edit test validation passed, creating test connection object");
                SmbConnection testConnection = new SmbConnection();
                testConnection.setServer(server);
                testConnection.setShare(share);
                testConnection.setUsername(usernameEditText.getText().toString().trim());
                testConnection.setPassword(passwordEditText.getText().toString().trim());
                testConnection.setDomain(domainEditText.getText().toString().trim());

                LogUtils.i("MainActivity", "Testing connection to server: " + server + " (from edit dialog)");
                testConnection(testConnection);
            }
        });
    }

    /**
     * Shows a confirmation dialog for deleting a connection.
     */
    private void confirmDeleteConnection(SmbConnection connection) {
        LogUtils.d("MainActivity", "Showing delete confirmation dialog for: " + connection.getName());
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_connection).setMessage(getString(R.string.confirm_delete_connection, connection.getName())).setPositiveButton(R.string.delete, (dialog, which) -> {
            LogUtils.i("MainActivity", "Confirming deletion of connection: " + connection.getName());
            viewModel.deleteConnection(connection.getId());
        }).setNegativeButton(R.string.cancel, (dialog, which) -> {
            LogUtils.d("MainActivity", "Deletion cancelled for connection: " + connection.getName());
        }).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        LogUtils.d("MainActivity", "Creating options menu");
        getMenuInflater().inflate(R.menu.menu_main, menu);
        LogUtils.d("MainActivity", "Options menu created with " + menu.size() + " items");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_system_monitor) {
            LogUtils.d("MainActivity", "System Monitor menu item selected");
            Intent intent = SystemMonitorActivity.createIntent(this);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Shows the network scan dialog to discover SMB servers.
     */
    private void showNetworkScanDialog(com.google.android.material.textfield.TextInputEditText serverEditText, com.google.android.material.textfield.TextInputEditText nameEditText) {
        LogUtils.d("MainActivity", "Showing network scan dialog");

        // Check if scanning is supported
        if (!networkScanner.isScanningSupported()) {
            EnhancedUIUtils.showError(this, "Network scanning is not available. Please check your network connection.");
            return;
        }

        // Inflate the dialog layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_network_scan, null);

        // Get UI elements with null checks
        View scanProgressSection = dialogView.findViewById(R.id.scan_progress_section);
        View serverListSection = dialogView.findViewById(R.id.server_list_section);
        ProgressBar progressIndicator = dialogView.findViewById(R.id.scan_progress_indicator);
        TextView scanStatusText = dialogView.findViewById(R.id.scan_status_text);
        TextView scanProgressText = dialogView.findViewById(R.id.scan_progress_text);
        TextView serversFoundLabel = dialogView.findViewById(R.id.servers_found_label);
        androidx.recyclerview.widget.RecyclerView serversRecyclerView = dialogView.findViewById(R.id.servers_recycler_view);
        TextView noServersText = dialogView.findViewById(R.id.no_servers_text);
        LinearLayout customButtonBar = dialogView.findViewById(R.id.custom_button_bar);
        Button customCancelButton = dialogView.findViewById(R.id.btn_cancel);
        Button customScanButton = dialogView.findViewById(R.id.btn_scan);
        Button customUseServerButton = dialogView.findViewById(R.id.btn_use_server);

        // Check for critical UI elements
        if (customButtonBar == null) {
            LogUtils.e("MainActivity", "Critical UI element customButtonBar is null - dialog layout may be missing elements");
            EnhancedUIUtils.showError(this, "Network scan dialog layout is incomplete. Please check the app installation.");
            return;
        }

        if (serversRecyclerView == null) {
            LogUtils.e("MainActivity", "Critical UI element serversRecyclerView is null");
            EnhancedUIUtils.showError(this, "Network scan dialog is not properly configured.");
            return;
        }

        // Set up RecyclerView
        DiscoveredServerAdapter adapter = new DiscoveredServerAdapter();
        serversRecyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        serversRecyclerView.setAdapter(adapter);

        // Create the dialog without default buttons (use custom ones)
        AlertDialog scanDialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.scan_network).setView(dialogView).create();

        // Set up custom buttons
        scanDialog.setOnShowListener(dialogInterface -> {
            // Show custom button bar
            if (customButtonBar != null) {
                customButtonBar.setVisibility(View.VISIBLE);
            }

            // Initially hide the "OK" button - will be shown when server is selected
            if (customUseServerButton != null) {
                customUseServerButton.setVisibility(View.GONE);
            }

            // Set up button click listeners
            if (customCancelButton != null) {
                customCancelButton.setOnClickListener(v -> {
                    LogUtils.d("MainActivity", "Network scan cancelled by user");
                    networkScanner.cancelScan();
                    scanDialog.dismiss();
                });
            }

            if (customScanButton != null) {
                customScanButton.setOnClickListener(v -> {
                    LogUtils.d("MainActivity", "Starting network scan");
                    startNetworkScan(scanProgressSection, serverListSection, progressIndicator, scanStatusText, scanProgressText, serversFoundLabel, noServersText, adapter, customScanButton, customUseServerButton);
                });
            }

            if (customUseServerButton != null) {
                customUseServerButton.setOnClickListener(v -> {
                    NetworkScanner.DiscoveredServer selectedServer = adapter.getSelectedServer();
                    if (selectedServer != null) {
                        LogUtils.d("MainActivity", "Using selected server: " + selectedServer.getDisplayName());

                        // Auto-fill the connection form
                        serverEditText.setText(selectedServer.getIpAddress());

                        // Generate a name if empty
                        if (nameEditText.getText().toString().trim().isEmpty()) {
                            String suggestedName = selectedServer.getHostname() != null ? selectedServer.getHostname() : "Server " + selectedServer.getIpAddress();
                            nameEditText.setText(suggestedName);
                        }

                        EnhancedUIUtils.showInfo(MainActivity.this, getString(R.string.server_info_applied, selectedServer.getDisplayName()));
                        scanDialog.dismiss();
                    }
                    // Note: Since OK button is only visible when server is selected, 
                    // the else case should never happen
                });
            }
        });

        // Set up server selection
        adapter.setOnServerSelectedListener(server -> {
            LogUtils.d("MainActivity", "Server selected: " + server.getDisplayName());
            // Show and update the "OK" button when server is selected
            if (customUseServerButton != null) {
                customUseServerButton.setVisibility(View.VISIBLE);
                customUseServerButton.setText(R.string.ok);
            }
        });

        // Show the dialog
        scanDialog.show();

        // Start initial scan automatically
        startNetworkScan(scanProgressSection, serverListSection, progressIndicator, scanStatusText, scanProgressText, serversFoundLabel, noServersText, adapter, customScanButton, customUseServerButton);
    }

    /**
     * Starts the network scan operation.
     */
    private void startNetworkScan(View scanProgressSection, View serverListSection, ProgressBar progressIndicator, TextView scanStatusText, TextView scanProgressText, TextView serversFoundLabel, TextView noServersText, DiscoveredServerAdapter adapter, Button scanButton, Button okButton) {

        LogUtils.d("MainActivity", "Starting network scan operation");

        // Show progress section, hide server list (with null checks)
        if (scanProgressSection != null) {
            scanProgressSection.setVisibility(View.VISIBLE);
        }
        if (serverListSection != null) {
            serverListSection.setVisibility(View.GONE);
        }

        // Hide both scan and OK buttons during scanning
        if (scanButton != null) {
            scanButton.setVisibility(View.GONE);
        }
        if (okButton != null) {
            okButton.setVisibility(View.GONE);
        }

        // Reset progress (with null checks)
        if (progressIndicator != null) {
            progressIndicator.setProgress(0);
        }
        if (scanStatusText != null) {
            scanStatusText.setText(R.string.scanning_network);
        }
        if (scanProgressText != null) {
            scanProgressText.setText(R.string.hosts_scan_progress);
        }

        // Clear previous results
        if (adapter != null) {
            adapter.setServers(new java.util.ArrayList<>());
        }

        // Start the scan
        networkScanner.scanLocalNetwork(new NetworkScanner.ScanProgressListener() {
            @Override
            public void onProgressUpdate(int scannedHosts, int totalHosts, String currentHost) {
                runOnUiThread(() -> {
                    int progress = (int) ((scannedHosts / (float) totalHosts) * 100);
                    if (progressIndicator != null) {
                        progressIndicator.setProgress(progress);
                    }
                    if (scanProgressText != null) {
                        scanProgressText.setText(getString(R.string.hosts_scanned, scannedHosts, totalHosts));
                    }
                    if (scanStatusText != null) {
                        scanStatusText.setText(getString(R.string.currently_scanning, currentHost));
                    }
                });
            }

            @Override
            public void onServerFound(NetworkScanner.DiscoveredServer server) {
                runOnUiThread(() -> {
                    LogUtils.d("MainActivity", "Found SMB server: " + server.getDisplayName());
                    if (adapter != null) {
                        adapter.addServer(server);
                        // Update the label with count
                        int serverCount = adapter.getItemCount();
                        LogUtils.d("MainActivity", "Added server to adapter. New count: " + serverCount);
                        if (serversFoundLabel != null) {
                            serversFoundLabel.setText(getString(R.string.servers_found_count, serverCount));
                        }
                    }
                });
            }

            @Override
            public void onScanComplete(java.util.List<NetworkScanner.DiscoveredServer> servers) {
                runOnUiThread(() -> {
                    int serverCount = (adapter != null) ? adapter.getItemCount() : 0;
                    LogUtils.i("MainActivity", "Network scan completed. Scanner found " + servers.size() + " servers, adapter has " + serverCount + " servers");

                    // Hide progress section, show results (with null checks)
                    if (scanProgressSection != null) {
                        scanProgressSection.setVisibility(View.GONE);
                    }
                    if (serverListSection != null) {
                        serverListSection.setVisibility(View.VISIBLE);
                    }

                    // Show scan button again
                    if (scanButton != null) {
                        scanButton.setVisibility(View.VISIBLE);
                        scanButton.setEnabled(true);
                        scanButton.setText(R.string.scan_network);
                    }

                    if (serverCount == 0) {
                        if (noServersText != null) {
                            noServersText.setVisibility(View.VISIBLE);
                        }
                        if (serversFoundLabel != null) {
                            serversFoundLabel.setVisibility(View.GONE);
                        }
                        // Keep OK button hidden when no servers found
                        if (okButton != null) {
                            okButton.setVisibility(View.GONE);
                        }
                    } else {
                        if (noServersText != null) {
                            noServersText.setVisibility(View.GONE);
                        }
                        if (serversFoundLabel != null) {
                            serversFoundLabel.setVisibility(View.VISIBLE);
                            serversFoundLabel.setText(getString(R.string.servers_found_count, serverCount));
                        }
                        // OK button will be shown when user selects a server
                    }

                    EnhancedUIUtils.showInfo(MainActivity.this, getString(R.string.scan_complete));
                });
            }

            @Override
            public void onScanError(String error) {
                runOnUiThread(() -> {
                    LogUtils.e("MainActivity", "Network scan error: " + error);

                    // Hide progress section, show results (with null checks)
                    if (scanProgressSection != null) {
                        scanProgressSection.setVisibility(View.GONE);
                    }
                    if (serverListSection != null) {
                        serverListSection.setVisibility(View.VISIBLE);
                    }

                    // Show scan button again
                    if (scanButton != null) {
                        scanButton.setVisibility(View.VISIBLE);
                        scanButton.setEnabled(true);
                        scanButton.setText(R.string.scan_network);
                    }

                    // Keep OK button hidden on error
                    if (okButton != null) {
                        okButton.setVisibility(View.GONE);
                    }

                    // Show error
                    EnhancedUIUtils.showError(MainActivity.this, getString(R.string.scan_error, error));
                    if (noServersText != null) {
                        noServersText.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    /**
     * Checks if the server address is complete enough to attempt a connection.
     * Validates IP addresses (xxx.xxx.xxx.xxx) and hostnames.
     */
    private boolean isValidServerAddress(String serverAddress) {
        if (serverAddress == null || serverAddress.trim().isEmpty()) {
            return false;
        }

        String trimmed = serverAddress.trim();

        // Check if it looks like a complete IP address
        if (trimmed.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return true;
        }

        // Check if it looks like a hostname (contains at least one letter and no incomplete IP patterns)
        if (trimmed.matches(".*[a-zA-Z].*") && !trimmed.matches("\\d+\\.\\d*\\.?\\d*\\.?\\d*")) {
            return trimmed.length() >= 3; // Minimum reasonable hostname length
        }

        return false;
    }

    /**
     * Discovers available shares on the specified server.
     */
    private void discoverShares(String server, String username, String password, String domain, View sharesSection, ProgressBar sharesProgress, SharesAdapter sharesAdapter, TextView sharesStatusText) {
        LogUtils.d("MainActivity", "Discovering shares on server: " + server);

        // Show shares section and progress
        sharesSection.setVisibility(View.VISIBLE);
        sharesProgress.setVisibility(View.VISIBLE);
        sharesStatusText.setText(R.string.discovering_shares);

        // Clear previous shares
        sharesAdapter.clearShares();

        // Create a temporary connection for share discovery
        SmbConnection tempConnection = new SmbConnection();
        tempConnection.setServer(server);
        tempConnection.setUsername(username);
        tempConnection.setPassword(password);
        tempConnection.setDomain(domain);

        viewModel.listShares(tempConnection, new MainViewModel.ShareListCallback() {
            @Override
            public void onSuccess(java.util.List<String> shares) {
                runOnUiThread(() -> {
                    LogUtils.d("MainActivity", "Found " + shares.size() + " shares on server: " + server);
                    sharesProgress.setVisibility(View.GONE);

                    if (shares.isEmpty()) {
                        sharesStatusText.setText(R.string.no_shares_found);
                    } else {
                        sharesStatusText.setText(R.string.tap_share_to_select);
                        sharesAdapter.setShares(shares);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    LogUtils.w("MainActivity", "Failed to discover shares: " + error);
                    sharesProgress.setVisibility(View.GONE);
                    sharesStatusText.setText(R.string.could_not_connect_check_credentials);
                    sharesAdapter.clearShares();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up network scanner
        if (networkScanner != null) {
            networkScanner.shutdown();
        }
        LogUtils.d("MainActivity", "onDestroy called");
    }

    /**
     * Checks battery optimization settings on first app start
     */
    private void checkBatteryOptimizationOnFirstRun() {
        if (BatteryOptimizationUtils.shouldShowBatteryOptimizationDialog(this)) {
            // Show dialog only after short delay so MainActivity is fully loaded
            findViewById(android.R.id.content).postDelayed(() -> {
                BatteryOptimizationUtils.requestBatteryOptimizationExemption(this);
            }, 2000); // 2 seconds delay
        }
    }
}
