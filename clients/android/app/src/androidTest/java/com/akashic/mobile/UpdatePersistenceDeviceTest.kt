package com.akashic.mobile

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akashic.mobile.data.local.ConversationEntity
import com.akashic.mobile.data.local.MessageEntity
import com.akashic.mobile.data.local.ServerProfileEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdatePersistenceDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<App>()

    /** 写入隔离的配对身份和运行中会话，供原地升级与视觉验收复用。 */
    @Test
    fun seedPairedStateBeforeUpdate() = runBlocking {
        // 1. 建立与真实配对结果相同 owner 的设备身份
        val keys = app.container.deviceKeyStore
        val alias = keys.aliasForServer(SERVER_ID)
        if (!keys.contains(alias)) keys.create(alias)
        app.container.database.serverProfiles().upsert(
            ServerProfileEntity(
                serverId = SERVER_ID,
                displayName = "Pixel 7 视觉验收",
                deviceId = "review-device",
                keyAlias = alias,
                applicationKeyFingerprint = "review-fingerprint",
                lanEndpointsJson = "[]",
                tunnelEndpointsJson = "[]",
                tlsSpkiPinsJson = "[]",
                createdAt = System.currentTimeMillis(),
            ),
        )

        // 2. 保存一条带运行态的会话投影并选择它
        app.container.database.conversations().upsert(
            ConversationEntity(SESSION_ID, SERVER_ID, "视觉验收会话", System.currentTimeMillis()),
        )
        app.container.database.messages().upsert(
            MessageEntity(
                messageId = "review-running-message",
                clientMessageId = null,
                sessionId = SESSION_ID,
                role = "assistant",
                text = "正在验证抽屉、胶囊和设置页。",
                deliveryState = "streaming",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        app.container.preferences.selectServer(SERVER_ID)
        app.container.preferences.selectSession(SESSION_ID)
    }

    /** 确认覆盖安装后 Room、DataStore 与 Android Keystore 身份都仍存在。 */
    @Test
    fun pairedStateSurvivesInPlaceUpdate() = runBlocking {
        val profile = app.container.database.serverProfiles().get(SERVER_ID)
        val settings = app.container.preferences.settings.first()

        assertNotNull(profile)
        assertEquals(SERVER_ID, settings.currentServerId)
        assertEquals(SESSION_ID, settings.currentSessionId)
        assertTrue(app.container.deviceKeyStore.contains(requireNotNull(profile).keyAlias))
    }

    private companion object {
        const val SERVER_ID = "review-update-server"
        const val SESSION_ID = "mobile:00000000-0000-0000-0000-000000000701"
    }
}
