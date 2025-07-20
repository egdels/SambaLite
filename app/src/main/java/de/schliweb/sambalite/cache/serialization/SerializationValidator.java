package de.schliweb.sambalite.cache.serialization;

import de.schliweb.sambalite.cache.exception.CacheExceptionHandler;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.util.LogUtils;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.List;

/**
 * Validates that objects are properly serializable before they are stored in the cache.
 * This class centralizes the serialization testing logic that was previously scattered
 * throughout the original IntelligentCacheManager.
 */
public class SerializationValidator {
    private static final String TAG = "SerializationValidator";

    // Exception handler for reporting serialization errors
    private final CacheExceptionHandler exceptionHandler;

    /**
     * Creates a new SerializationValidator.
     *
     * @param exceptionHandler The exception handler for reporting serialization errors
     */
    public SerializationValidator(CacheExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Validates that an object is properly serializable.
     *
     * @param obj The object to validate
     * @param key The cache key associated with the object (for error reporting)
     * @param <T> The type of the object
     * @return true if the object is properly serializable, false otherwise
     */
    public <T> boolean validateSerializable(T obj, String key) {
        if (obj == null) {
            return true; // null is technically serializable
        }

        // Check if the object implements Serializable
        if (!(obj instanceof Serializable)) {
            LogUtils.e(TAG, "Data is not serializable for key: " + key + ", type: " + obj.getClass().getSimpleName());
            return false;
        }

        // Special validation for List data
        if (obj instanceof List<?>) {
            return validateList((List<?>) obj, key);
        }

        // Test serialization of the object
        return testSerialization(obj, key);
    }

    /**
     * Validates that a list and its items are properly serializable.
     *
     * @param list The list to validate
     * @param key  The cache key associated with the list (for error reporting)
     * @return true if the list and its items are properly serializable, false otherwise
     */
    private boolean validateList(List<?> list, String key) {
        LogUtils.d(TAG, "Validating List data with " + list.size() + " items for key: " + key);

        // Check a sample of items in the list
        int sampleSize = Math.min(list.size(), 5); // Check first 5 items
        for (int i = 0; i < sampleSize; i++) {
            Object item = list.get(i);
            if (item == null) {
                LogUtils.w(TAG, "Found null item at index " + i + " in list for key: " + key);
                continue;
            }

            LogUtils.d(TAG, "List item " + i + ": " + item.getClass().getSimpleName());

            // Check if the item implements Serializable
            if (!(item instanceof Serializable)) {
                LogUtils.e(TAG, "Non-serializable item found at index " + i + " for key: " + key + ", type: " + item.getClass().getSimpleName());
                return false;
            }

            // Special validation for SmbFileItem
            if (item instanceof SmbFileItem) {
                if (!validateSmbFileItem((SmbFileItem) item, key, i)) {
                    return false;
                }
            }
        }

        // Test serialization of the entire list
        return testSerialization(list, key);
    }

    /**
     * Validates that an SmbFileItem is properly serializable.
     *
     * @param fileItem The SmbFileItem to validate
     * @param key      The cache key associated with the item (for error reporting)
     * @param index    The index of the item in the list (for error reporting)
     * @return true if the SmbFileItem is properly serializable, false otherwise
     */
    private boolean validateSmbFileItem(SmbFileItem fileItem, String key, int index) {
        LogUtils.d(TAG, "SmbFileItem validation - name: " + fileItem.getName() + ", path: " + fileItem.getPath() + ", isDirectory: " + fileItem.isDirectory());

        try {
            // Validate name field
            if (fileItem.getName() != null) {
                String nameClass = fileItem.getName().getClass().getSimpleName();
                if (!(fileItem.getName() instanceof String)) {
                    LogUtils.e(TAG, "SmbFileItem name is not String: " + nameClass + " - value: " + fileItem.getName());
                    throw new ClassCastException("SmbFileItem name field is not String: " + nameClass);
                }
                LogUtils.d(TAG, "Name field validated as String: " + fileItem.getName());
            }

            // Validate path field
            if (fileItem.getPath() != null) {
                String pathClass = fileItem.getPath().getClass().getSimpleName();
                if (!(fileItem.getPath() instanceof String)) {
                    LogUtils.e(TAG, "SmbFileItem path is not String: " + pathClass + " - value: " + fileItem.getPath());
                    throw new ClassCastException("SmbFileItem path field is not String: " + pathClass);
                }
                LogUtils.d(TAG, "Path field validated as String: " + fileItem.getPath());
            }

            // Test serialization of this specific item
            return testSerialization(fileItem, key + "_item_" + index);

        } catch (Exception e) {
            exceptionHandler.handleException(e, "SmbFileItem field validation failed for key: " + key + ", index: " + index);
            return false;
        }
    }

    /**
     * Tests that an object can be serialized.
     *
     * @param obj The object to test
     * @param key The cache key associated with the object (for error reporting)
     * @return true if the object can be serialized, false otherwise
     */
    private boolean testSerialization(Object obj, String key) {
        try {
            LogUtils.d(TAG, "Testing serialization for key: " + key);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            oos.close();
            byte[] serializedData = baos.toByteArray();
            LogUtils.d(TAG, "Serialization test successful for key: " + key + ", size: " + serializedData.length + " bytes");
            return true;
        } catch (Exception e) {
            exceptionHandler.handleException(e, "Serialization test failed for key: " + key);
            return false;
        }
    }

    /**
     * Validates and serializes an object.
     *
     * @param obj The object to validate and serialize
     * @param key The cache key associated with the object (for error reporting)
     * @return The serialized object, or null if validation or serialization failed
     */
    public byte[] validateAndSerialize(Object obj, String key) {
        if (!validateSerializable(obj, key)) {
            return null;
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            oos.close();
            return baos.toByteArray();
        } catch (Exception e) {
            exceptionHandler.handleException(e, "Serialization failed for key: " + key);
            return null;
        }
    }
}