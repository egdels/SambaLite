package de.schliweb.sambalite.di;

import android.app.Application;
import android.content.Context;
import dagger.Module;
import dagger.Provides;
import de.schliweb.sambalite.data.repository.ConnectionRepository;
import de.schliweb.sambalite.data.repository.ConnectionRepositoryImpl;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.data.repository.SmbRepositoryImpl;
import de.schliweb.sambalite.data.background.BackgroundSmbManager;
import de.schliweb.sambalite.util.LogUtils;

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
}
