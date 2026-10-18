package vegabobo.dsusideloader.installer.privileged

import android.content.Intent
import android.util.Log
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.model.Session
import vegabobo.dsusideloader.service.PrivilegedProvider

/**
 * Install images via DSU app
 * Supported modes are: Shizuku (as shell or root), root and system
 *
 * Android adapter of [DsuSubmission]: the request parameters are captured when the handler
 * is created (later changes of the shared session don't affect it), and [submission] must
 * be run by the caller inside the task's coroutine. [DsuSubmission.Result.Submitted] means
 * the request was handed to the DSU system app; it does NOT mean the installation succeeded.
 */
class DsuInstallationHandler(
    session: Session,
    sdCardRemounter: SdCardRemounter,
    bootId: String? = null,
    /** Identity/cancel check of the task that owns this request (see [DsuSubmission]). */
    isOwner: () -> Boolean = { true },
) {

    private val tag = this.javaClass.simpleName

    private val userdataSize = session.userSelection.userSelectedUserdata
    private val fileUri = session.dsuInstallation.uri
    private val length = session.dsuInstallation.fileSize

    val submission = DsuSubmission(
        remounter = sdCardRemounter,
        unmountSdCard = session.preferences.isUnmountSdCard,
        bootId = bootId,
        connect = { PrivilegedProvider.awaitService()?.let { ServiceTarget(it) } },
        log = { Log.d(tag, it) },
        isOwner = isOwner,
    )

    private inner class ServiceTarget(private val service: IPrivilegedService) : DsuSubmitTarget {
        override val volumeOps: VolumeOps = service.asVolumeOps()

        override fun prepareDsuApp() {
            service.setDynProp()
            service.forceStopPackage("com.android.dynsystem")
        }

        override fun startDsuInstall() {
            val dynIntent = Intent()
            dynIntent.setClassName(
                "com.android.dynsystem",
                "com.android.dynsystem.VerificationActivity",
            )
            dynIntent.flags += Intent.FLAG_ACTIVITY_NEW_TASK
            dynIntent.action = "android.os.image.action.START_INSTALL"
            dynIntent.data = fileUri
            dynIntent.putExtra("KEY_USERDATA_SIZE", userdataSize)
            dynIntent.putExtra("KEY_SYSTEM_SIZE", length)

            Log.d(tag, "Starting DSU VerificationActivity: $dynIntent")
            service.startActivity(dynIntent)
        }
    }
}
