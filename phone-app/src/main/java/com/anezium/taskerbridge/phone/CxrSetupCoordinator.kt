package com.anezium.taskerbridge.phone

import android.os.SystemClock

enum class CxrSetupAction {
    INSTALL,
    LAUNCH,
}

sealed interface CxrSetupStep {
    data object None : CxrSetupStep
    data object Connect : CxrSetupStep
    data class Run(val action: CxrSetupAction) : CxrSetupStep
}

class CxrSetupCoordinator(
    private val connectCooldownMs: Long,
) {
    private var pendingAction: CxrSetupAction? = null
    private var operationRunning = false
    private var lastConnectAttemptAtMs = 0L

    fun begin(action: CxrSetupAction) {
        pendingAction = action
        operationRunning = false
    }

    fun cancel() {
        pendingAction = null
        operationRunning = false
    }

    fun finishOperation() {
        pendingAction = null
        operationRunning = false
    }

    fun isIdle(): Boolean =
        pendingAction == null && !operationRunning

    fun nextStep(
        authorized: Boolean,
        setupConnected: Boolean,
        forceConnect: Boolean = false,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): CxrSetupStep {
        val action = pendingAction ?: return CxrSetupStep.None
        if (operationRunning || !authorized) return CxrSetupStep.None
        if (!setupConnected) {
            if (!forceConnect && nowMs - lastConnectAttemptAtMs < connectCooldownMs) {
                return CxrSetupStep.None
            }
            lastConnectAttemptAtMs = nowMs
            return CxrSetupStep.Connect
        }
        operationRunning = true
        return CxrSetupStep.Run(action)
    }
}
