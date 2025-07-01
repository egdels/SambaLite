package de.schliweb.sambalite.di;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import de.schliweb.sambalite.util.LogUtils;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.Map;

/**
 * Factory for creating ViewModels with Dagger.
 */
@Singleton
public class ViewModelFactory implements ViewModelProvider.Factory {

    private final Map<Class<? extends ViewModel>, Provider<ViewModel>> creators;

    @Inject
    public ViewModelFactory(Map<Class<? extends ViewModel>, Provider<ViewModel>> creators) {
        LogUtils.d("ViewModelFactory", "Initializing ViewModelFactory with " + creators.size() + " ViewModel providers");
        this.creators = creators;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        LogUtils.d("ViewModelFactory", "Creating ViewModel of type: " + modelClass.getSimpleName());
        Provider<? extends ViewModel> creator = creators.get(modelClass);
        if (creator == null) {
            LogUtils.d("ViewModelFactory", "No exact match found for " + modelClass.getSimpleName() + ", searching for assignable types");
            for (Map.Entry<Class<? extends ViewModel>, Provider<ViewModel>> entry : creators.entrySet()) {
                if (modelClass.isAssignableFrom(entry.getKey())) {
                    creator = entry.getValue();
                    LogUtils.d("ViewModelFactory", "Found assignable type: " + entry.getKey().getSimpleName());
                    break;
                }
            }
        }
        if (creator == null) {
            LogUtils.e("ViewModelFactory", "Unknown ViewModel class: " + modelClass.getName());
            throw new IllegalArgumentException("Unknown model class " + modelClass);
        }
        try {
            LogUtils.i("ViewModelFactory", "Successfully created ViewModel: " + modelClass.getSimpleName());
            return (T) creator.get();
        } catch (Exception e) {
            LogUtils.e("ViewModelFactory", "Error creating ViewModel: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
