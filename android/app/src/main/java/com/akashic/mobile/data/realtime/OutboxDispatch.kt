package com.akashic.mobile.data.realtime

internal const val CLOSE_COMMAND_REJECTED = 4410

internal enum class OutboxFailureDisposition {
    RETRY_ORIGINAL,
    FAIL,
}

internal fun messageSendFailureDisposition(errorCode: String?): OutboxFailureDisposition =
    if (errorCode == "command_outcome_unknown") {
        OutboxFailureDisposition.RETRY_ORIGINAL
    } else {
        OutboxFailureDisposition.FAIL
    }

internal class SingleFlightOutbox {
    var commandId: String? = null
        private set

    fun claim(nextCommandId: String): Boolean {
        if (commandId != null) return false
        commandId = nextCommandId
        return true
    }

    fun complete(completedCommandId: String) {
        check(commandId == completedCommandId) {
            "Outbox reply does not match the active command: expected=$commandId actual=$completedCommandId"
        }
        commandId = null
    }

    fun reset() {
        commandId = null
    }
}
