package com.umer_tf.ads.domain.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Singleton class for managing time-related operations.
 * Thread-safe implementation using ReadWriteLock.
 */
object TimeManager {
    private const val TAG = "MediationModule"
    private var isRunning = false
    private var startTime: Long = 0
    private val lock = ReentrantReadWriteLock()

    /**
     * Sets the start time.
     * @param value The start time value.
     */
    fun setStartTime(value: Int) {
        lock.write {
            startTime = value.toLong()
        }
    }

    /**
     * Gets the instance of [TimeManager].
     * @return The instance of [TimeManager].
     */
    @JvmStatic
    fun getInstance(): TimeManager {
        return this
    }

    /**
     * Starts the time manager.
     */
    fun start() {
        lock.write {
            if (!isRunning) {
                Log.d(TAG, "TimeManager started")
                isRunning = true
                startTime = System.currentTimeMillis()
            } else {
                Log.d(TAG, "TimeManager is already running")
            }
        }
    }

    /**
     * Stops the time manager.
     */
    fun stop() {
        lock.write {
            if (isRunning) {
                Log.d(TAG, "TimeManager stopped")
                isRunning = false
                // You can perform any cleanup or stop tasks here
            } else {
                Log.d(TAG, "TimeManager is not running")
            }
        }
    }

    /**
     * Resets the time manager.
     */
    fun reset() {
        lock.write {
            Log.d(TAG, "TimeManager reset")
            isRunning = true
            startTime = System.currentTimeMillis()
        }
    }

    /**
     * Gets the current time.
     * @return The current time in "HH:mm:ss" format.
     */
    fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    /**
     * Gets the elapsed time since start.
     * @return The elapsed time in milliseconds.
     */
    fun getElapsedTime(): Long {
        return lock.read {
            if (isRunning) {
                System.currentTimeMillis() - startTime
            } else {
                0
            }
        }
    }

    /**
     * Gets the elapsed time since start in seconds.
     * @return The elapsed time in seconds.
     */
    fun getElapsedTimeInSecs(): Long {
        return lock.read {
            if (isRunning) {
                (System.currentTimeMillis() - startTime) / 1000 // Convert to seconds
            } else {
                0
            }
        }
    }
}