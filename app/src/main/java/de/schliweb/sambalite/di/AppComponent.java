package de.schliweb.sambalite.di;

import android.app.Application;
import dagger.BindsInstance;
import dagger.Component;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.ui.FileBrowserActivity;
import de.schliweb.sambalite.ui.MainActivity;

import javax.inject.Singleton;

/**
 * Main Dagger component for the application.
 * Defines the scope and modules for dependency injection.
 */
@Singleton
@Component(modules = {AppModule.class, ViewModelModule.class})
public interface AppComponent {

    /**
     * Injects dependencies into the SambaLiteApp.
     */
    void inject(SambaLiteApp app);

    /**
     * Injects dependencies into the MainActivity.
     */
    void inject(MainActivity activity);

    /**
     * Injects dependencies into the RefactoredFileBrowserActivity.
     */
    void inject(FileBrowserActivity activity);

    /**
     * Builder for the AppComponent.
     */
    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder application(Application application);

        AppComponent build();
    }
}
