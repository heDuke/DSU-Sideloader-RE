package vegabobo.dsusideloader.installer.task

import vegabobo.dsusideloader.installer.root.DsuInstallResult
import vegabobo.dsusideloader.preparation.InstallationStep

/** UI-facing terminal outcome of a root installation, including cleanup results. */
data class InstallOutcome(val step: InstallationStep, val detail: String) {

    companion object {
        fun from(result: DsuInstallResult): InstallOutcome = when (result) {
            is DsuInstallResult.Success -> InstallOutcome(InstallationStep.INSTALL_SUCCESS_REBOOT_DYN_OS, "")
            is DsuInstallResult.Failed -> {
                val cleanup = result.cleanup
                if (cleanup != null && !cleanup.succeeded) {
                    InstallOutcome(
                        InstallationStep.ERROR_CLEANUP_INCOMPLETE,
                        "${result.step}: ${result.info}\n${cleanup.describe()}",
                    )
                } else {
                    InstallOutcome(result.step, result.info)
                }
            }
            is DsuInstallResult.Cancelled -> {
                val cleanup = result.cleanup
                if (cleanup == null || cleanup.succeeded) {
                    InstallOutcome(InstallationStep.ERROR_CANCELED, cleanup?.describe() ?: "")
                } else {
                    InstallOutcome(InstallationStep.ERROR_CLEANUP_INCOMPLETE, "cancelled\n${cleanup.describe()}")
                }
            }
        }
    }
}
