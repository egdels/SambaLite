package de.schliweb.sambalite.di;

import android.app.Application;
import android.content.Context;
import dagger.Module;
import dagger.Provides;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.data.repository.ConnectionRepository;
import de.schliweb.sambalite.data.repository.ConnectionRepositoryImpl;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.data.repository.SmbRepositoryImpl;
import de.schliweb.sambalite.ui.FileBrowserState;
import de.schliweb.sambalite.ui.controllers.FileBrowserUIState;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.PreferencesManager;

import javax.inject.Singleton;

/**
 * Dagger module that provides application-level dependencies.
 */
@Module
public class AppModule {

    /**
     * Provides the application context.
     */
    @Provides
    @Singleton
    Context provideContext(Application application) {
        LogUtils.d("AppModule", "Providing application context");
        return application;
    }

    /**
     * Provides the BackgroundSmbManager for background-aware SMB operations.
     */
    @Provides
    @Singleton
    BackgroundSmbManager provideBackgroundSmbManager(Context context) {
        LogUtils.d("AppModule", "Providing BackgroundSmbManager");
        return new BackgroundSmbManager(context);
    }

    /**
     * Provides the SmbRepository implementation.
     */
    @Provides
    @Singleton
    SmbRepository provideSmbRepository(SmbRepositoryImpl repository) {
        LogUtils.d("AppModule", "Providing SmbRepository implementation");
        return repository;
    }

    /**
     * Provides the ConnectionRepository implementation.
     */
    @Provides
    @Singleton
    ConnectionRepository provideConnectionRepository(ConnectionRepositoryImpl repository) {
        LogUtils.d("AppModule", "Providing ConnectionRepository implementation");
        return repository;
    }

    /**
     * Provides the PreferencesManager as a singleton for storing UI preferences.
     */
    @Provides
    @Singleton
    PreferencesManager providePreferencesManager(Context context) {
        LogUtils.d("AppModule", "Providing PreferencesManager");
        return new PreferencesManager(context);
    }

    /**
     * Provides the FileBrowserState as a singleton to be shared between ViewModels.
     */
    @Provides
    @Singleton
    FileBrowserState provideFileBrowserState(PreferencesManager preferencesManager) {
        LogUtils.d("AppModule", "Providing FileBrowserState");
        return new FileBrowserState(preferencesManager);
    }

    /**
     * Provides the FileBrowserUIState as a singleton to be shared between controllers.
     */
    @Provides
    @Singleton
    FileBrowserUIState provideFileBrowserUIState() {
        LogUtils.d("AppModule", "Providing FileBrowserUIState");
        return new FileBrowserUIState();
    }
}
