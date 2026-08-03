package com.akashic.mobile.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "mobile_webui_state",
    foreignKeys = [
        ForeignKey(
            entity = ServerProfileEntity::class,
            parentColumns = ["serverId"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MobileWebUiStateEntity(
    @androidx.room.PrimaryKey val serverId: String,
    val desiredChannel: String,
    val desiredTargetKey: String?,
    val desiredGenerationId: String?,
    val desiredManifestDigest: String?,
    val releaseEpoch: String?,
    val releaseSequence: Long?,
    val selectionDigest: String?,
    val servingGenerationId: String?,
    val fallbackGenerationId: String?,
    val attemptingGenerationId: String?,
    val attemptingNonce: String?,
    val attemptingStartedAt: Long?,
    val lastHealthyAt: Long?,
    val updatedAt: Long,
)

@Entity(
    tableName = "mobile_webui_generations",
    primaryKeys = ["serverId", "generationId"],
    foreignKeys = [
        ForeignKey(
            entity = ServerProfileEntity::class,
            parentColumns = ["serverId"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId"), Index("manifestDigest"), Index("lastUsedAt")],
)
data class MobileWebUiGenerationEntity(
    val serverId: String,
    val generationId: String,
    val targetKey: String,
    val manifestDigest: String,
    val manifestPath: String,
    val entrypoint: String,
    val bridgeProtocolMin: Int,
    val bridgeProtocolMax: Int,
    val snapshotProtocolMin: Int,
    val snapshotProtocolMax: Int,
    val minimumNativeBuild: Int,
    val platformsJson: String,
    val sourceRepository: String,
    val sourceCommit: String,
    val sourceTree: String,
    val inputDigest: String,
    val buildContextDigest: String,
    val dirtyProvenanceJson: String?,
    val reproducible: Boolean,
    val builderIdentityJson: String,
    val unpackedSizeBytes: Long,
    val fileCount: Int,
    val verifiedAt: Long,
    val lastUsedAt: Long,
)

@Entity(
    tableName = "mobile_webui_blobs",
    primaryKeys = ["serverId", "sha256"],
    foreignKeys = [
        ForeignKey(
            entity = ServerProfileEntity::class,
            parentColumns = ["serverId"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId"), Index("lastUsedAt")],
)
data class MobileWebUiBlobEntity(
    val serverId: String,
    val sha256: String,
    val bytes: Long,
    val relativePath: String,
    val lastUsedAt: Long,
)

@Entity(
    tableName = "mobile_webui_rejects",
    primaryKeys = ["serverId", "targetKey", "compatibilityFingerprint"],
    foreignKeys = [
        ForeignKey(
            entity = ServerProfileEntity::class,
            parentColumns = ["serverId"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId"), Index("retryAfter")],
)
data class MobileWebUiRejectEntity(
    val serverId: String,
    val targetKey: String,
    val compatibilityFingerprint: String,
    val reason: String,
    val firstRejectedAt: Long,
    val retryAfter: Long?,
)
