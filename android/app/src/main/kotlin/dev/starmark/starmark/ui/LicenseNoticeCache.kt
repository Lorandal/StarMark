package dev.starmark.starmark.ui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bundled notices do not change during a process; avoid parsing them on every visit. */
internal object LicenseNoticeCache {
    @Volatile private var cached: List<LicenseNoticeGroup>? = null
    private val lock = Mutex()
    fun peek() = cached
    suspend fun load(read: () -> List<LicenseNoticeGroup>) = lock.withLock {
        cached ?: read().also { cached = it }
    }
}
