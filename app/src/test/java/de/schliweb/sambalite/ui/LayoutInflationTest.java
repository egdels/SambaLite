package de.schliweb.sambalite.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import de.schliweb.sambalite.R;

import static org.junit.Assert.assertNotNull;

/**
 * Test to verify that our custom layouts can be inflated without errors.
 * This helps catch XML inflation issues early in development.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = de.schliweb.sambalite.SambaLiteApp.class)
public class LayoutInflationTest {

    @Test
    public void testDialogAddConnectionInflation() {
        Context context = ApplicationProvider.getApplicationContext();
        context.setTheme(R.style.Theme_SambaLite);
        LayoutInflater inflater = LayoutInflater.from(context);
        
        // This should not throw an exception
        View view = inflater.inflate(R.layout.dialog_add_connection, null);
        assertNotNull("dialog_add_connection layout should inflate successfully", view);
    }

    @Test
    public void testDialogLoadingInflation() {
        Context context = ApplicationProvider.getApplicationContext();
        context.setTheme(R.style.Theme_SambaLite);
        LayoutInflater inflater = LayoutInflater.from(context);
        
        // This should not throw an exception
        View view = inflater.inflate(R.layout.dialog_loading, null);
        assertNotNull("dialog_loading layout should inflate successfully", view);
    }

    @Test
    public void testDialogNetworkScanInflation() {
        Context context = ApplicationProvider.getApplicationContext();
        context.setTheme(R.style.Theme_SambaLite);
        LayoutInflater inflater = LayoutInflater.from(context);
        
        // This should not throw an exception
        View view = inflater.inflate(R.layout.dialog_network_scan, null);
        assertNotNull("dialog_network_scan layout should inflate successfully", view);
    }

    @Test
    public void testItemDiscoveredServerInflation() {
        Context context = ApplicationProvider.getApplicationContext();
        context.setTheme(R.style.Theme_SambaLite);
        LayoutInflater inflater = LayoutInflater.from(context);
        
        // This should not throw an exception
        View view = inflater.inflate(R.layout.item_discovered_server, null);
        assertNotNull("item_discovered_server layout should inflate successfully", view);
    }
}
