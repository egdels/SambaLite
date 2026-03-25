/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.di;

import android.app.Application;
import androidx.annotation.NonNull;
import dagger.BindsInstance;
import dagger.Component;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.ui.FileBrowserActivity;
import de.schliweb.sambalite.ui.MainActivity;
import de.schliweb.sambalite.ui.ShareReceiverActivity;
import de.schliweb.sambalite.ui.SystemMonitorActivity;
import javax.inject.Singleton;

/**
 * Main Dagger component for the application. Defines the scope and modules for dependency
 * injection.
 */
@Singleton
@Component(modules = {AppModule.class, ViewModelModule.class})
public interface AppComponent {

  /** Injects dependencies into the SambaLiteApp. */
  void inject(@NonNull SambaLiteApp app);

  /** Injects dependencies into the MainActivity. */
  void inject(@NonNull MainActivity activity);

  /** Injects dependencies into the FileBrowserActivity. */
  void inject(@NonNull FileBrowserActivity activity);

  /** Injects dependencies into the ShareReceiverActivity. */
  void inject(@NonNull ShareReceiverActivity activity);

  /** Injects dependencies into the SystemMonitorActivity. */
  void inject(@NonNull SystemMonitorActivity activity);

  /** Builder for the AppComponent. */
  @Component.Builder
  interface Builder {
    @BindsInstance
    @NonNull
    Builder application(@NonNull Application application);

    @NonNull
    AppComponent build();
  }
}
