package de.schliweb.sambalite.cache.exception;

import de.schliweb.sambalite.cache.statistics.CacheStatistics;
import de.schliweb.sambalite.util.LogUtils;

import java.io.IOException;
import java.io.InvalidClassException;
import java.io.NotSerializableException;
import java.io.StreamCorruptedException;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Centralizes error handling for cache operations.
 * This class reduces the exception density in the codebase by providing methods for handling
 * common cache-related exceptions in a standardized way.
 */
public class CacheExceptionHandler {
    private static final String TAG = "CacheExceptionHandler";

    // Statistics for tracking error counts
    private final CacheStatistics statistics;

    /**
     * Creates a new CacheExceptionHandler.
     *
     * @param statistics The statistics object for tracking error counts
     */
    public CacheExceptionHandler(CacheStatistics statistics) {
        this.statistics = statistics;
    }

    /**
     * Executes a cache operation with standardized error handling.
     *
     * @param operation    The operation to execute
     * @param errorMessage The error message to log if an exception occurs
     * @param <T>          The return type of the operation
     * @return The result of the operation, or null if an exception occurred
     */
    public <T> T executeCacheOperation(Callable<T> operation, String errorMessage) {
        try {
            return operation.call();
        } catch (Exception e) {
            handleException(e, errorMessage);
            return null;
        }
    }

    /**
     * Executes a cache operation with standardized error handling and a fallback value.
     *
     * @param operation    The operation to execute
     * @param fallback     The fallback value to return if an exception occurs
     * @param errorMessage The error message to log if an exception occurs
     * @param <T>          The return type of the operation
     * @return The result of the operation, or the fallback value if an exception occurred
     */
    public <T> T executeCacheOperation(Callable<T> operation, T fallback, String errorMessage) {
        try {
            return operation.call();
        } catch (Exception e) {
            handleException(e, errorMessage);
            return fallback;
        }
    }

    /**
     * Executes a cache operation with standardized error handling and a custom exception handler.
     *
     * @param operation        The operation to execute
     * @param exceptionHandler The custom exception handler
     * @param errorMessage     The error message to log if an exception occurs
     * @param <T>              The return type of the operation
     * @return The result of the operation, or the result of the exception handler if an exception occurred
     */
    public <T> T executeCacheOperation(Callable<T> operation, Function<Exception, T> exceptionHandler, String errorMessage) {
        try {
            return operation.call();
        } catch (Exception e) {
            handleException(e, errorMessage);
            return exceptionHandler.apply(e);
        }
    }

    /**
     * Executes a cache operation that doesn't return a value with standardized error handling.
     *
     * @param operation    The operation to execute
     * @param errorMessage The error message to log if an exception occurs
     */
    public void executeVoidCacheOperation(Runnable operation, String errorMessage) {
        try {
            operation.run();
        } catch (Exception e) {
            handleException(e, errorMessage);
        }
    }

    /**
     * Executes a cache operation that doesn't return a value with standardized error handling and a custom exception handler.
     *
     * @param operation        The operation to execute
     * @param exceptionHandler The custom exception handler
     * @param errorMessage     The error message to log if an exception occurs
     */
    public void executeVoidCacheOperation(Runnable operation, Consumer<Exception> exceptionHandler, String errorMessage) {
        try {
            operation.run();
        } catch (Exception e) {
            handleException(e, errorMessage);
            exceptionHandler.accept(e);
        }
    }

    /**
     * Handles an exception by logging it and updating statistics.
     *
     * @param e            The exception to handle
     * @param errorMessage The error message to log
     */
    public void handleException(Exception e, String errorMessage) {
        LogUtils.e(TAG, errorMessage + ": " + e.getMessage());
        LogUtils.e(TAG, "Exception type: " + e.getClass().getSimpleName());
        LogUtils.e(TAG, "Exception stack trace: " + android.util.Log.getStackTraceString(e));

        // Update statistics based on exception type
        if (e instanceof NotSerializableException || e instanceof InvalidClassException) {
            statistics.incrementSerializationErrors();
        } else if (e instanceof StreamCorruptedException || e instanceof ClassNotFoundException || e instanceof ClassCastException) {
            statistics.incrementDeserializationErrors();
        } else if (e instanceof IOException) {
            if (errorMessage.contains("read") || errorMessage.contains("load")) {
                statistics.incrementDiskReadErrors();
            } else if (errorMessage.contains("write") || errorMessage.contains("save")) {
                statistics.incrementDiskWriteErrors();
            }
        }
    }

    /**
     * Validates that an object is serializable.
     *
     * @param obj          The object to validate
     * @param errorMessage The error message to log if the object is not serializable
     * @return true if the object is serializable, false otherwise
     */
    public boolean validateSerializable(Object obj, String errorMessage) {
        if (obj == null) {
            return true; // null is technically serializable
        }

        if (!(obj instanceof java.io.Serializable)) {
            LogUtils.e(TAG, errorMessage + ": Object is not serializable, type: " + obj.getClass().getSimpleName());
            statistics.incrementSerializationErrors();
            return false;
        }

        return true;
    }

    /**
     * Validates that a class cast is valid.
     *
     * @param obj          The object to cast
     * @param targetClass  The target class
     * @param errorMessage The error message to log if the cast is not valid
     * @param <T>          The target type
     * @return The cast object, or null if the cast is not valid
     */
    @SuppressWarnings("unchecked")
    public <T> T validateCast(Object obj, Class<T> targetClass, String errorMessage) {
        if (obj == null) {
            return null;
        }

        if (!targetClass.isInstance(obj)) {
            LogUtils.e(TAG, errorMessage + ": Invalid cast from " + obj.getClass().getSimpleName() + " to " + targetClass.getSimpleName());
            statistics.incrementDeserializationErrors();
            return null;
        }

        return (T) obj;
    }
}