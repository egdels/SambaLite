package de.schliweb.sambalite.util;

import android.content.Context;
import android.content.SharedPreferences;
import de.schliweb.sambalite.ui.FileSortOption;

/**
 * Manager for handling application preferences.
 * This class provides methods for storing and retrieving UI preferences,
 * such as sorting options.
 */
public class PreferencesManager {
    private static final String PREFS_NAME = "de.schliweb.sambalite.preferences";
    private static final String KEY_SORT_OPTION = "sort_option";
    private static final String KEY_DIRECTORIES_FIRST = "directories_first";

    private final SharedPreferences preferences;

    /**
     * Creates a new PreferencesManager.
     *
     * @param context The application context
     */
    public PreferencesManager(Context context) {
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        LogUtils.d("PreferencesManager", "PreferencesManager initialized");
    }

    /**
     * Saves the sort option.
     *
     * @param sortOption The sort option to save
     */
    public void saveSortOption(FileSortOption sortOption) {
        LogUtils.d("PreferencesManager", "Saving sort option: " + sortOption);
        preferences.edit().putString(KEY_SORT_OPTION, sortOption.name()).apply();
    }

    /**
     * Gets the saved sort option.
     *
     * @return The saved sort option, or NAME if none is saved
     */
    public FileSortOption getSortOption() {
        String sortOptionName = preferences.getString(KEY_SORT_OPTION, FileSortOption.NAME.name());
        try {
            FileSortOption option = FileSortOption.valueOf(sortOptionName);
            LogUtils.d("PreferencesManager", "Retrieved sort option: " + option);
            return option;
        } catch (IllegalArgumentException e) {
            LogUtils.w("PreferencesManager", "Invalid sort option name: " + sortOptionName + ", using default");
            return FileSortOption.NAME;
        }
    }

    /**
     * Saves the directories-first flag.
     *
     * @param directoriesFirst Whether to show directories first
     */
    public void saveDirectoriesFirst(boolean directoriesFirst) {
        LogUtils.d("PreferencesManager", "Saving directories first: " + directoriesFirst);
        preferences.edit().putBoolean(KEY_DIRECTORIES_FIRST, directoriesFirst).apply();
    }

    /**
     * Gets the saved directories-first flag.
     *
     * @return The saved directories-first flag, or true if none is saved
     */
    public boolean getDirectoriesFirst() {
        boolean directoriesFirst = preferences.getBoolean(KEY_DIRECTORIES_FIRST, true);
        LogUtils.d("PreferencesManager", "Retrieved directories first: " + directoriesFirst);
        return directoriesFirst;
    }
}