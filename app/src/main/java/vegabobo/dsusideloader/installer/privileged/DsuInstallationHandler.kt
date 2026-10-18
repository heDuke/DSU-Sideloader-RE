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
 * [onSubmitted] means the request was handed to the DSU system app; it does NOT mean the
 * installation succeeded (that can only be confirmed by logs). [onError] is called when
 * the request could not be submitted.
 */
open class DsuInstallationHandler(
    private val session: Session,
    private val sdCardRemounter: SdCardRemounter,
    private val onSubmitted: () -> Unit = {},
    private val onError: (String) -> Unit = {},
) {

    private val tag = this.javaClass.simpleName

    fun startInstallation() {
        PrivilegedProvider.run(onFail = { onError("Privileged service unavailable") }) {
            try {
                if (session.preferences.isUnmountSdCard) {
                    sdCardRemounter.unmountPublicVolumes(asVolumeOps())
                }
                forwardInstallationToDSU(this)
                onSubmitted()
            } catch (e: Exception) {
                Log.e(tag, "Failed to submit installation to DSU", e)
                onError(e.message ?: e.toString())
            }
        }
    }

    private fun forwardInstallationToDSU(service: IPrivilegedService) {
        val userdataSize = session.userSelection.userSelectedUserdata
        val fileUri = session.dsuInstallation.uri
        val length = session.dsuInstallation.fileSize

        service.run {
            setDynProp()
            forceStopPackage("com.android.dynsystem")

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
            startActivity(dynIntent)
        }
    }
}
