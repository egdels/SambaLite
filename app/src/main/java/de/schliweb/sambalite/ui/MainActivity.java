package de.schliweb.sambalite.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.util.KeyboardUtils;
import de.schliweb.sambalite.util.LogUtils;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LogUtils.d("MainActivity", "onCreate called");
        // Get the Dagger component and inject dependencies
        AppComponent appComponent = ((SambaLiteApp) getApplication()).getAppComponent();
        appComponent.inject(this);
        LogUtils.d("MainActivity", "Dependencies injected");

        super.onCreate(savedInstanceState);
        // Randlose Anzeige aktivieren
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);


        setContentView(R.layout.activity_main);
        LogUtils.d("MainActivity", "Content view set");


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

        // Set up FAB for adding new connections
        FloatingActionButton fab = findViewById(R.id.fab_add_connection);
        fab.setOnClickListener(v -> showAddConnectionDialog());
        LogUtils.d("MainActivity", "FAB set up");

        // Observe connections
        viewModel.getConnections().observe(this, connections -> {
            LogUtils.d("MainActivity", "Connections updated: " + connections.size() + " connections");
            adapter.setConnections(connections);
            if (connections.isEmpty()) {
                LogUtils.d("MainActivity", "No connections available");
                // Show empty state
                // TODO: Add empty state view
            }
        });

        // Observe error messages
        viewModel.getErrorMessage().observe(this, errorMessage -> {
            if (errorMessage != null && !errorMessage.isEmpty()) {
                LogUtils.w("MainActivity", "Error message received: " + errorMessage);
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
            }
        });

        // Observe loading state
        viewModel.isLoading().observe(this, isLoading -> {
            LogUtils.d("MainActivity", "Loading state changed: " + isLoading);
            // TODO: Show/hide loading indicator
        });

        LogUtils.i("MainActivity", "MainActivity initialized");
    }

    @Override
    public void onConnectionClick(SmbConnection connection) {
        LogUtils.d("MainActivity", "Connection clicked: " + connection.getName());
        // Open file browser activity for this connection
        Intent intent = FileBrowserActivity.createIntent(this, connection.getId());
        startActivity(intent);
        LogUtils.i("MainActivity", "Opening FileBrowserActivity for connection: " + connection.getName());
    }

    @Override
    public void onConnectionLongClick(SmbConnection connection) {
        LogUtils.d("MainActivity", "Connection long-clicked: " + connection.getName());
        // Show options menu (edit, delete, etc.)
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
        com.google.android.material.textfield.TextInputLayout usernameLayout = dialogView.findViewById(R.id.username_layout);
        com.google.android.material.textfield.TextInputLayout passwordLayout = dialogView.findViewById(R.id.password_layout);
        com.google.android.material.textfield.TextInputLayout domainLayout = dialogView.findViewById(R.id.domain_layout);

        com.google.android.material.textfield.TextInputEditText nameEditText = dialogView.findViewById(R.id.name_edit_text);
        com.google.android.material.textfield.TextInputEditText serverEditText = dialogView.findViewById(R.id.server_edit_text);
        com.google.android.material.textfield.TextInputEditText shareEditText = dialogView.findViewById(R.id.share_edit_text);
        com.google.android.material.textfield.TextInputEditText usernameEditText = dialogView.findViewById(R.id.username_edit_text);
        com.google.android.material.textfield.TextInputEditText passwordEditText = dialogView.findViewById(R.id.password_edit_text);
        com.google.android.material.textfield.TextInputEditText domainEditText = dialogView.findViewById(R.id.domain_edit_text);

        Button testConnectionButton = dialogView.findViewById(R.id.test_connection_button);

        // Create the dialog
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.add_new_connection).setView(dialogView).setPositiveButton(R.string.save, null) // Set to null initially to prevent auto-dismiss
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
    }

    /**
     * Shows a dialog with options for a connection.
     */
    private void showConnectionOptionsDialog(SmbConnection connection) {
        LogUtils.d("MainActivity", "Showing options dialog for connection: " + connection.getName());
        String[] options = {"Edit", "Test Connection", "Delete"};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
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
        com.google.android.material.textfield.TextInputLayout usernameLayout = dialogView.findViewById(R.id.username_layout);
        com.google.android.material.textfield.TextInputLayout passwordLayout = dialogView.findViewById(R.id.password_layout);
        com.google.android.material.textfield.TextInputLayout domainLayout = dialogView.findViewById(R.id.domain_layout);

        com.google.android.material.textfield.TextInputEditText nameEditText = dialogView.findViewById(R.id.name_edit_text);
        com.google.android.material.textfield.TextInputEditText serverEditText = dialogView.findViewById(R.id.server_edit_text);
        com.google.android.material.textfield.TextInputEditText shareEditText = dialogView.findViewById(R.id.share_edit_text);
        com.google.android.material.textfield.TextInputEditText usernameEditText = dialogView.findViewById(R.id.username_edit_text);
        com.google.android.material.textfield.TextInputEditText passwordEditText = dialogView.findViewById(R.id.password_edit_text);
        com.google.android.material.textfield.TextInputEditText domainEditText = dialogView.findViewById(R.id.domain_edit_text);

        Button testConnectionButton = dialogView.findViewById(R.id.test_connection_button);

        // Pre-populate fields with existing connection data
        nameEditText.setText(connection.getName());
        serverEditText.setText(connection.getServer());
        shareEditText.setText(connection.getShare());
        usernameEditText.setText(connection.getUsername());
        passwordEditText.setText(connection.getPassword());
        domainEditText.setText(connection.getDomain());

        // Create the dialog
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.edit_connection).setView(dialogView).setPositiveButton(R.string.save, null) // Set to null initially to prevent auto-dismiss
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
        new AlertDialog.Builder(this).setTitle(R.string.delete_connection).setMessage(getString(R.string.confirm_delete_connection, connection.getName())).setPositiveButton(R.string.delete, (dialog, which) -> {
            LogUtils.i("MainActivity", "Confirming deletion of connection: " + connection.getName());
            viewModel.deleteConnection(connection.getId());
        }).setNegativeButton(R.string.cancel, (dialog, which) -> {
            LogUtils.d("MainActivity", "Deletion cancelled for connection: " + connection.getName());
        }).show();
    }
}
