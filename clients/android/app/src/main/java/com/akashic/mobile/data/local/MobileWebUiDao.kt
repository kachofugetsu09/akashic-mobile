package com.akashic.mobile.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface MobileWebUiDao {
    @Query("SELECT * FROM mobile_webui_state WHERE serverId = :serverId")
    suspend fun getState(serverId: String): MobileWebUiStateEntity?

    @Query("SELECT * FROM mobile_webui_state")
    suspend fun listStates(): List<MobileWebUiStateEntity>

    @Upsert
    suspend fun upsertState(state: MobileWebUiStateEntity)

    @Query("DELETE FROM mobile_webui_state WHERE serverId = :serverId")
    suspend fun deleteState(serverId: String): Int

    @Query(
        "SELECT * FROM mobile_webui_generations WHERE serverId = :serverId AND generationId = :generationId",
    )
    suspend fun getGeneration(serverId: String, generationId: String): MobileWebUiGenerationEntity?

    @Query("SELECT * FROM mobile_webui_generations WHERE serverId = :serverId")
    suspend fun listGenerations(serverId: String): List<MobileWebUiGenerationEntity>

    @Query("SELECT * FROM mobile_webui_generations")
    suspend fun listAllGenerations(): List<MobileWebUiGenerationEntity>

    @Upsert
    suspend fun upsertGeneration(generation: MobileWebUiGenerationEntity)

    @Query(
        "UPDATE mobile_webui_generations SET lastUsedAt = :lastUsedAt " +
            "WHERE serverId = :serverId AND generationId = :generationId",
    )
    suspend fun touchGeneration(serverId: String, generationId: String, lastUsedAt: Long): Int

    @Query(
        "DELETE FROM mobile_webui_generations WHERE serverId = :serverId AND generationId = :generationId",
    )
    suspend fun deleteGeneration(serverId: String, generationId: String): Int

    @Query("DELETE FROM mobile_webui_generations WHERE serverId = :serverId")
    suspend fun deleteGenerations(serverId: String): Int

    @Query(
        "SELECT * FROM mobile_webui_blobs WHERE serverId = :serverId AND sha256 = :sha256",
    )
    suspend fun getBlob(serverId: String, sha256: String): MobileWebUiBlobEntity?

    @Query("SELECT * FROM mobile_webui_blobs WHERE serverId = :serverId")
    suspend fun listBlobs(serverId: String): List<MobileWebUiBlobEntity>

    @Upsert
    suspend fun upsertBlob(blob: MobileWebUiBlobEntity)

    @Query("DELETE FROM mobile_webui_blobs WHERE serverId = :serverId AND sha256 = :sha256")
    suspend fun deleteBlob(serverId: String, sha256: String): Int

    @Query("DELETE FROM mobile_webui_blobs WHERE serverId = :serverId")
    suspend fun deleteBlobs(serverId: String): Int

    @Query(
        "SELECT * FROM mobile_webui_rejects " +
            "WHERE serverId = :serverId AND targetKey = :targetKey AND compatibilityFingerprint = :fingerprint",
    )
    suspend fun getReject(
        serverId: String,
        targetKey: String,
        fingerprint: String,
    ): MobileWebUiRejectEntity?

    @Upsert
    suspend fun upsertReject(reject: MobileWebUiRejectEntity)

    @Query(
        "DELETE FROM mobile_webui_rejects WHERE serverId = :serverId " +
            "AND targetKey = :targetKey AND compatibilityFingerprint = :fingerprint",
    )
    suspend fun deleteReject(serverId: String, targetKey: String, fingerprint: String): Int

    @Query("DELETE FROM mobile_webui_rejects WHERE serverId = :serverId")
    suspend fun deleteRejects(serverId: String): Int

    @Transaction
    suspend fun resetServerDurableState(
        serverId: String,
        baseline: MobileWebUiStateEntity,
        manualReject: MobileWebUiRejectEntity?,
    ) {
        deleteState(serverId)
        deleteGenerations(serverId)
        deleteBlobs(serverId)
        deleteRejects(serverId)
        upsertState(baseline)
        if (manualReject != null) upsertReject(manualReject)
    }

    @Transaction
    suspend fun recoverAttemptDurableState(
        state: MobileWebUiStateEntity,
        rejection: MobileWebUiRejectEntity?,
    ) {
        upsertState(state)
        if (rejection != null) upsertReject(rejection)
    }

    @Transaction
    suspend fun commitHealthyDurableState(
        state: MobileWebUiStateEntity,
        generationId: String,
        lastUsedAt: Long,
    ) {
        upsertState(state)
        check(touchGeneration(state.serverId, generationId, lastUsedAt) == 1) {
            "WebUI healthy generation 已不存在"
        }
    }

    @Transaction
    suspend fun rollbackAndRejectDurableState(
        state: MobileWebUiStateEntity,
        rejection: MobileWebUiRejectEntity?,
    ) {
        upsertState(state)
        if (rejection != null) upsertReject(rejection)
    }

    @Query(
        "SELECT EXISTS(SELECT 1 FROM mobile_webui_rejects " +
            "WHERE serverId = :serverId AND reason = 'manual_reset')",
    )
    suspend fun hasManualResetReject(serverId: String): Boolean
}
