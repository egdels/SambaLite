package de.schliweb.sambalite.test.utils;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Utility class for testing LiveData objects.
 * This class allows synchronous access to the values emitted by LiveData objects in tests.
 */
public class LiveDataTestUtil {

    /**
     * Gets the value from a LiveData object, waiting for it to have a value.
     *
     * @param liveData The LiveData object to observe.
     * @param <T>      The type of data held by the LiveData object.
     * @return The value of the LiveData object.
     * @throws InterruptedException If the waiting is interrupted.
     * @throws TimeoutException     If the LiveData doesn't emit a value within the timeout period.
     */
    public static <T> T getValue(final LiveData<T> liveData) throws InterruptedException, TimeoutException {
        final Object[] data = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        Observer<T> observer = new Observer<T>() {
            @Override
            public void onChanged(@Nullable T o) {
                data[0] = o;
                latch.countDown();
                liveData.removeObserver(this);
            }
        };

        liveData.observeForever(observer);

        // Wait for the LiveData to emit a value
        if (!latch.await(2, TimeUnit.SECONDS)) {
            liveData.removeObserver(observer);
            throw new TimeoutException("LiveData value was not set within timeout");
        }

        //noinspection unchecked
        return (T) data[0];
    }
}