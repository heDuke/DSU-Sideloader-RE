package vegabobo.dsusideloader.preparation

enum class InstallationStep {
    // Initial value
    NOT_INSTALLING,

    // When a DSU is detected on app startup
    DSU_ALREADY_INSTALLED,
    DSU_ALREADY_RUNNING_DYN_OS,

    // Preparation steps
    PROCESSING,
    COPYING_FILE,
    DECOMPRESSING_XZ,
    COMPRESSING_TO_GZ,
    VERIFYING_IMAGE_SIZE,
    VERIFYING_IMAGE_INTEGRITY,
    EXTRACTING_FILE,

    // Installation Steps
    REQUIRES_ADB_CMD_TO_CONTINUE,
    DISCARD_CURRENT_GSI,
    WAITING_USER_CONFIRMATION,
    PROCESSING_LOG_READABLE,
    INSTALLING,
    INSTALLING_ROOTED,
    CREATING_PARTITION,

    // Cancel requested; waiting for the task to stop and clean up (abort/remove)
    CANCELLING,

    // Errors
    ERROR,

    // Cleanup after a failure/cancel did not complete or the system state is unknown
    ERROR_CLEANUP_INCOMPLETE,
    CANCEL_REQUESTED_CLEANUP_UNVERIFIED,
    ERROR_CANCELED,
    ERROR_REQUIRES_DISCARD_DSU,
    ERROR_ALREADY_RUNNING_DYN_OS,
    ERROR_CREATE_PARTITION,
    ERROR_EXTERNAL_SDCARD_ALLOC,
    ERROR_NO_AVAIL_STORAGE,
    ERROR_F2FS_WRONG_PATH,
    ERROR_EXTENTS,
    ERROR_SELINUX,
    ERROR_SELINUX_ROOTLESS,

    // Request handed to the DSU system app, but the result can't be observed
    // (no READ_LOGS permission). Not a success state.
    INSTALL_SUBMITTED_UNCONFIRMED,
    STORAGE_RESTORE_PENDING,

    // Once installation is finished (confirmed)
    INSTALL_SUCCESS,
    INSTALL_SUCCESS_REBOOT_DYN_OS,
}
