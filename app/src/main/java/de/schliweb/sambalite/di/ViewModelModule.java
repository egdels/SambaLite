package de.schliweb.sambalite.di;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;
import de.schliweb.sambalite.ui.FileListViewModel;
import de.schliweb.sambalite.ui.MainViewModel;
import de.schliweb.sambalite.ui.SearchViewModel;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;

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
     * Binds the FileListViewModel to the ViewModelFactory.
     */
    @Binds
    @IntoMap
    @ViewModelKey(FileListViewModel.class)
    abstract ViewModel bindFileListViewModel(FileListViewModel viewModel);

    /**
     * Binds the FileOperationsViewModel to the ViewModelFactory.
     */
    @Binds
    @IntoMap
    @ViewModelKey(FileOperationsViewModel.class)
    abstract ViewModel bindFileOperationsViewModel(FileOperationsViewModel viewModel);

    /**
     * Binds the SearchViewModel to the ViewModelFactory.
     */
    @Binds
    @IntoMap
    @ViewModelKey(SearchViewModel.class)
    abstract ViewModel bindSearchViewModel(SearchViewModel viewModel);

    /**
     * Binds the ViewModelFactory.
     */
    @Binds
    abstract ViewModelProvider.Factory bindViewModelFactory(ViewModelFactory factory);
}
