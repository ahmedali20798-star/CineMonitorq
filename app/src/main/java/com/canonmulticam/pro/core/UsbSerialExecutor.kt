package com.canonmulticam.pro.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Dedicated single-thread executor for USB Bulk/Interrupt I/O.
 * Eliminates multi-thread packet interleaving on serial USB endpoints.
 */
class UsbSerialExecutor {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CanonUsbSerialThread").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
    }

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    fun shutdown() {
        runCatching { executor.shutdownNow() }
    }
}
