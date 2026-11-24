package com.ryanvidal.barfriend.core.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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

    internal suspend fun <T> runOptimisticMutationLocked(
        sessionRepository: SessionRepository,
        originEpoch: Long,
        originBarId: Int,
        applyLocal: suspend () -> Unit,
        runRemote: suspend () -> T,
        rollbackLocal: suspend () -> Unit,
    ): T {
        var appliedLocally = false
        return try {
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                sessionRepository.ensureSessionEpochValid(originEpoch, originBarId)
                appliedLocally = true
                bumpGenerationLocked()
                applyLocal()
            }
            runRemote()
        } catch (e: CancellationException) {
            if (appliedLocally) {
                withContext(NonCancellable) {
                    if (sessionRepository.isSessionEpochValid(originEpoch, originBarId)) {
                        bumpGenerationLocked()
                        rollbackLocal()
                    }
                }
            }
            throw e
        } catch (e: Throwable) {
            if (appliedLocally) {
                withContext(NonCancellable) {
                    if (sessionRepository.isSessionEpochValid(originEpoch, originBarId)) {
                        bumpGenerationLocked()
                        rollbackLocal()
                    }
                }
            }
            throw e
        }
    }
}
