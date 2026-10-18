package vegabobo.dsusideloader.installer.task

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One coroutine step of an installation task (preparation, DSU app submission).
 *
 * Exactly one of these happens per step:
 *  - the step calls [settle] when it produced the task's terminal outcome itself or handed
 *    the task over to the next step, or
 *  - the step's `onUnsettled` handler runs once, after the step's coroutine stopped
 *    (cancelled before it started, cancelled while suspended, failed, or returned without
 *    a result).
 *
 * So a cancelled or failing step can never leave the task without a terminal outcome
 * (and the task lock held forever), and the fallback never runs while the step body is
 * still executing.
 */
class TaskStep internal constructor() {
    private val settled = AtomicBoolean(false)

    /** @return true for the first caller only; whoever wins owns the terminal outcome. */
    fun settle(): Boolean = settled.compareAndSet(false, true)

    val isSettled: Boolean get() = settled.get()
}

object TaskSteps {
    /**
     * Launches [block] in [scope] with [context] (which should contain the task's Job, so the
     * step is cancelled with the task).
     *
     * [onUnsettled] receives the completion cause: a [CancellationException] when cancelled
     * (also before the body ever ran), the exception thrown by the body, or null when the
     * body returned without settling. Non-cancellation exceptions are caught here, so they
     * are reported to [onUnsettled] instead of escaping to the scope.
     */
    fun launch(
        scope: CoroutineScope,
        context: CoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        onUnsettled: (cause: Throwable?) -> Unit,
        /**
         * Anything thrown by the body after it settled (e.g. while handing over), including a
         * cancellation. Must produce the task's terminal outcome if nobody else owns it.
         */
        onErrorAfterSettle: (Throwable) -> Unit = {},
        block: suspend CoroutineScope.(TaskStep) -> Unit,
    ): Job {
        val step = TaskStep()
        val job = scope.launch(context, start) {
            try {
                block(step)
            } catch (e: CancellationException) {
                // After settling, the completion fallback no longer runs: whoever settled
                // (e.g. the hand-over that was interrupted) must still learn about it.
                if (step.isSettled) onErrorAfterSettle(e)
                throw e
            } catch (e: Throwable) {
                if (step.settle()) onUnsettled(e) else onErrorAfterSettle(e)
                if (e !is Exception) throw e
            }
        }
        // Runs after the coroutine completed, including "cancelled before start".
        job.invokeOnCompletion { cause -> if (step.settle()) onUnsettled(cause) }
        return job
    }
}

/**
 * The retry entries of the installation card (plain retry, retry after unmounting the SD card,
 * retry after the SELinux remedy) as steps of the task mechanism, like the first installation
 * (HomeViewModel.onConfirmInstallationSheet):
 *
 *  - Which task: a new task when none exists; the current one only while it waits in the DSU
 *    app (phase DSU_APP, not cancelling, its previous request finished). Anything else (a task
 *    still preparing / installing / cancelling, a retry or submission still in flight, i.e.
 *    repeated taps) is ignored, so two requests of one task never run in parallel.
 *  - Where: the step runs on [io] as a child of the task's own Job, so blocking work
 *    ([beforeStart], parameter reads, the ADB script write) never runs on the main thread and
 *    a cancel stops it.
 *  - Outcome: exactly one terminal per task. While the task is still PREPARING nobody else owns
 *    it, so an exception or cancel goes to [onPreparationEnded] (task workspace cleanup, then
 *    release). Once the root pipeline / DSU app step took over, their paths decide: a cancel
 *    is reported by the backend's cancel path (which waits for this step, see [retryJobOf]),
 *    other errors by [onTaskFailed].
 */
class RetryEntry(
    private val gate: InstallTaskGate,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    /** Starts a new task (HomeViewModel.beginTask); null if one is running. */
    private val beginTask: () -> Long?,
    /** True while the DSU app request of task id is still being submitted. */
    private val submissionInFlight: (id: Long) -> Boolean,
    /** Accepted (main thread, before the step runs): e.g. show PROCESSING. */
    private val onAccepted: (id: Long) -> Unit,
    /** Runs the installation of task id from inside the step (startInstallation(id, inStep = true)). */
    private val startInstallation: (id: Long) -> Unit,
    /** Terminal outcome of a task still PREPARING (HomeViewModel.onPreparationStepUnsettled). */
    private val onPreparationEnded: (id: Long, cause: Throwable?, inStep: Boolean) -> Unit,
    /** Failure after a backend took over (HomeViewModel.onTaskFailed; ignores cancelling tasks). */
    private val onTaskFailed: (id: Long, cause: Throwable) -> Unit,
    private val log: (String) -> Unit = {},
) {
    sealed class Result {
        data class Started(val taskId: Long, val job: Job) : Result()
        data class Ignored(val reason: String) : Result()
    }

    /** Runs a retry; [beforeStart] (e.g. the SELinux remedy) runs inside the step, on [io]. */
    fun retry(beforeStart: suspend () -> Unit = {}): Result {
        val current = gate.current()
        val id: Long
        val newTask: Boolean
        if (current == null) {
            id = beginTask() ?: return ignored("another task started")
            newTask = true
        } else {
            if (current.cancelling) return ignored("task ${current.id} is cancelling")
            if (current.phase != InstallTaskGate.Phase.DSU_APP) {
                return ignored("task ${current.id} is still ${current.phase}")
            }
            if (submissionInFlight(current.id)) return ignored("request of task ${current.id} still in flight")
            id = current.id
            newTask = false
        }
        val taskJob = gate.jobOf(id) ?: return ignored("task $id ended")
        val step = gate.attachRetry(id) {
            TaskSteps.launch(
                scope = scope,
                context = io + taskJob,
                start = CoroutineStart.LAZY,
                onUnsettled = { cause -> onStepEnded(id, cause, inStep = false) },
                onErrorAfterSettle = { e -> onStepEnded(id, e, inStep = true) },
            ) { s ->
                beforeStart()
                if (s.settle()) startInstallation(id)
            }
        }
        if (step == null) {
            // Lost a race (a retry already in flight, or a cancel). A task begun here must
            // still end; an existing task is owned by whoever is running it.
            if (newTask) onPreparationEnded(id, CancellationException("retry not started"), false)
            return ignored("retry of task $id already in flight or cancelled")
        }
        onAccepted(id)
        step.start()
        return Result.Started(id, step)
    }

    private fun onStepEnded(id: Long, cause: Throwable?, inStep: Boolean) {
        when {
            // Completion handlers can run after join() returned: the task may be gone already.
            gate.jobOf(id) == null -> log("Retry step of ended task $id finished: $cause")
            gate.isOwnedByPreparation(id) -> onPreparationEnded(id, cause, inStep)
            // The backend's cancel path waits for this step and reports the task.
            gate.isCancelling(id) -> Unit
            else -> onTaskFailed(id, cause ?: IllegalStateException("Retry ended without a result"))
        }
    }

    private fun ignored(reason: String): Result {
        log("Retry ignored: $reason")
        return Result.Ignored(reason)
    }
}
