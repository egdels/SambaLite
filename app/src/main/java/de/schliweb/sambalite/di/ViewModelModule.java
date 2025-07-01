package de.schliweb.sambalite.di;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;
import de.schliweb.sambalite.ui.FileBrowserViewModel;
import de.schliweb.sambalite.ui.MainViewModel;

/**
 * Dagger module for providing ViewModels.
 */
@Module
public abstract class ViewModelModule {

    /**
     * Binds the MainViewModel to the ViewModelFactory.
     */
    @Binds
    @IntoMap
    @ViewModelKey(MainViewModel.class)
    abstract ViewModel bindMainViewModel(MainViewModel viewModel);

    /**
     * Binds the FileBrowserViewModel to the ViewModelFactory.
     */
    @Binds
    @IntoMap
    @ViewModelKey(FileBrowserViewModel.class)
    abstract ViewModel bindFileBrowserViewModel(FileBrowserViewModel viewModel);

    /**
     * Binds the ViewModelFactory.
     */
    @Binds
    abstract ViewModelProvider.Factory bindViewModelFactory(ViewModelFactory factory);
}
