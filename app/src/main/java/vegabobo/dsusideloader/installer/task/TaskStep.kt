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
