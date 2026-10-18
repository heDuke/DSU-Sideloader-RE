package vegabobo.dsusideloader.installer.privileged

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What the submission needs from the privileged service (abstracted for tests). */
interface DsuSubmitTarget {
    val volumeOps: VolumeOps

    /** setDynProp + force-stop the DSU system app. */
    fun prepareDsuApp()

    /** Opens the DSU system app's install confirmation (startActivity with the captured request). */
    fun startDsuInstall()
}

/**
 * Hands one installation request to the DSU system app, as a cancellable step of the task.
 *
 * Runs in the task's coroutine (child of the installation Job), so cancelling the task
 * cancels it and the caller can wait for it. Cancellation is checked after waiting for the
 * service, before and after unmounting, and right before startActivity. The last check and
 * [cancel] are serialised: once [cancel] returned false, startActivity will not be called.
 *
 * [isOwner] is the task's own identity check (current task and not cancelling); it is
 * re-checked at every stage and under the lock right before startActivity, so a request of a
 * task that was cancelled or replaced never reaches the system, whatever Job it runs on.
 */
class DsuSubmission(
    private val remounter: SdCardRemounter,
    private val unmountSdCard: Boolean,
    private val bootId: String?,
    private val connect: suspend () -> DsuSubmitTarget?,
    private val log: (String) -> Unit = {},
    private val isOwner: () -> Boolean = { true },
) {
    sealed class Result {
        object Submitted : Result()
        object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    private val lock = Any()
    private var cancelled = false
    private var reachedSystem = false

    /** True once startActivity was (or is being) called. */
    val hasReachedSystem: Boolean get() = synchronized(lock) { reachedSystem }

    /**
     * Stops the request from reaching the system.
     * @return true if it already reached the system (startActivity called or in progress).
     */
    fun cancel(): Boolean = synchronized(lock) {
        cancelled = true
        reachedSystem
    }

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (synchronized(lock) { cancelled } || !isOwner()) throw CancellationException("submission cancelled")
    }

    suspend fun submit(): Result {
        try {
            val target = connect() ?: return Result.Failed("Privileged service unavailable")
            checkActive()
            if (unmountSdCard) {
                remounter.unmountPublicVolumes(target.volumeOps, bootId)
                checkActive()
            }
            target.prepareDsuApp()
            checkActive()
            // From here on the system DSU app may own the outcome: the remount record must not
            // be resolved automatically anymore (see RemountPolicy).
            remounter.markHandedToSystem()
            checkActive()
            synchronized(lock) {
                if (cancelled || !isOwner()) return Result.Cancelled
                reachedSystem = true
            }
            target.startDsuInstall()
            return Result.Submitted
        } catch (e: CancellationException) {
            log("Submission cancelled before reaching the system")
            return Result.Cancelled
        } catch (e: Exception) {
            log("Failed to submit installation to DSU: $e")
            return Result.Failed(e.message ?: e.toString())
        }
    }
}

/** A running [DsuSubmission] step of task [taskId]. */
class DsuSubmissionStep(val taskId: Long, val submission: DsuSubmission, val job: kotlinx.coroutines.Job) {
    /**
     * Cancels the request and waits until its step has stopped, so no unmount or
     * startActivity of this request can happen after this returns.
     * @return true if the request reached the system (cleanup in the DSU app is needed).
     */
    suspend fun cancelAndJoin(): Boolean {
        val reached = submission.cancel()
        job.cancel()
        job.join()
        return reached || submission.hasReachedSystem
    }
}
