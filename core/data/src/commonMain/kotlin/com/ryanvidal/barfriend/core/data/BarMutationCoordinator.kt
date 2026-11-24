package com.ryanvidal.barfriend.core.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@SingleIn(AppScope::class)
@Inject
class BarMutationCoordinator {
    val mutex: Mutex = Mutex()
    private var generation: Long = 0L

    suspend inline fun <T> withMutationLock(crossinline block: suspend () -> T): T =
        mutex.withLock { block() }

    fun currentGenerationLocked(): Long = generation

    fun bumpGenerationLocked(): Long {
        generation += 1L
        return generation
    }
}
