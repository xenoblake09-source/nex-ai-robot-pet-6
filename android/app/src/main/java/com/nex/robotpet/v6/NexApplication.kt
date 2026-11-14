package com.nex.robotpet.v6

import android.app.Application
import android.util.Log

/**
 * Application entry point for NEX 6.0 (com.nex.robotpet.v6).
 * Installs a secret-safe diagnostic uncaught-exception logger before delegating to the OS handler.
 */
class NexApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("NEX6", "Unhandled exception on thread=${thread.name}: ${throwable.javaClass.simpleName}")
            } catch (_: Throwable) {
                // Safe logging guard
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
