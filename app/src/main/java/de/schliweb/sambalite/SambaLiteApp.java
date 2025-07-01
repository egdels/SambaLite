package de.schliweb.sambalite;

import android.app.Application;
import de.schliweb.sambalite.di.AppComponent;
import de.schliweb.sambalite.di.DaggerAppComponent;
import de.schliweb.sambalite.util.LogUtils;
import lombok.Getter;

/**
 * Main Application class for SambaLite.
 * Initializes Dagger component and other app-wide configurations.
 */
@Getter
public class SambaLiteApp extends Application {

    private AppComponent appComponent;

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize Timber logging
        LogUtils.init(BuildConfig.DEBUG);

        // Initialize Dagger
        appComponent = DaggerAppComponent.builder().application(this).build();

        // Inject dependencies into the application
        appComponent.inject(this);

        LogUtils.i("SambaLiteApp initialized");
    }

}
