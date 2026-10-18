package com.topjohnwu.superuser
// libsu boundary for the SELinux remedy (onClickSetSeLinuxPermissive).
object Shell {
    class Result(val isSuccess: Boolean, val err: List<String> = emptyList())
    class Job(private val command: String) { fun exec(): Result = hook(command) }
    @Volatile var hook: (String) -> Result = { Result(true) }
    fun cmd(vararg commands: String) = Job(commands.joinToString("; "))
}
