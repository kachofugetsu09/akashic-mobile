package com.akashic.mobile.data.realtime

import java.text.Normalizer
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** 已认证移动端 WebUI release lane 交换的协议值。 */
@Serializable
data class MobileWebUiReleaseView(
    @SerialName("server_id") val serverId: String,
    @SerialName("release_epoch") val releaseEpoch: String,
    val sequence: Long,
    @SerialName("selection_digest") val selectionDigest: String,
    val stable: MobileWebUiTarget?,
    val preview: MobileWebUiTarget?,
)

@Serializable
internal data class MobileWebUiReleaseChangedPayload(
    @SerialName("server_id") val serverId: String,
    @SerialName("selection_digest") val selectionDigest: String,
)

@Serializable
data class MobileWebUiTarget(
    @SerialName("target_key") val targetKey: String,
    @SerialName("generation_id") val generationId: String,
    @SerialName("manifest_digest") val manifestDigest: String,
    @SerialName("manifest_size_bytes") val manifestSizeBytes: Long,
    @SerialName("bridge_protocol_min") val bridgeProtocolMin: Int,
    @SerialName("bridge_protocol_max") val bridgeProtocolMax: Int,
    @SerialName("snapshot_protocol_min") val snapshotProtocolMin: Int,
    @SerialName("snapshot_protocol_max") val snapshotProtocolMax: Int,
    @SerialName("minimum_native_build") val minimumNativeBuild: Int,
    val platforms: List<String>,
)

@Serializable
data class MobileWebUiManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("generation_id") val generationId: String,
    val entrypoint: String,
    val files: List<MobileWebUiManifestFile>,
    @SerialName("bridge_protocol_min") val bridgeProtocolMin: Int,
    @SerialName("bridge_protocol_max") val bridgeProtocolMax: Int,
    @SerialName("snapshot_protocol_min") val snapshotProtocolMin: Int,
    @SerialName("snapshot_protocol_max") val snapshotProtocolMax: Int,
    @SerialName("minimum_native_build") val minimumNativeBuild: Int,
    val platforms: List<String>,
    @SerialName("source_repository") val sourceRepository: String,
    @SerialName("source_commit") val sourceCommit: String,
    @SerialName("source_tree") val sourceTree: String,
    @SerialName("input_digest") val inputDigest: String,
    @SerialName("build_context_digest") val buildContextDigest: String,
    @SerialName("dirty_provenance") val dirtyProvenance: MobileWebUiDirtyProvenance?,
    val reproducible: Boolean,
    @SerialName("builder_identity") val builderIdentity: MobileWebUiBuilderIdentity,
    @SerialName("unpacked_size_bytes") val unpackedSizeBytes: Long,
    @SerialName("file_count") val fileCount: Int,
)

@Serializable
data class MobileWebUiManifestFile(
    val path: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val mime: String,
)

@Serializable
data class MobileWebUiDirtyProvenance(
    @SerialName("base_commit") val baseCommit: String,
    @SerialName("tracked_patch_digest") val trackedPatchDigest: String,
    @SerialName("untracked_tree_digest") val untrackedTreeDigest: String,
)

@Serializable
data class MobileWebUiBuilderIdentity(
    @SerialName("node_version") val nodeVersion: String,
    @SerialName("npm_version") val npmVersion: String,
    @SerialName("package_lock_digest") val packageLockDigest: String,
    @SerialName("build_script_digest") val buildScriptDigest: String,
)

@Serializable
data class MobileWebUiContentPreparePayload(
    @SerialName("target_key") val targetKey: String,
)

@Serializable
data class MobileWebUiContentGrant(
    @SerialName("target_key") val targetKey: String,
    @SerialName("manifest_digest") val manifestDigest: String,
    val ticket: String,
    @SerialName("expires_at") val expiresAt: String,
)

/** 在内容 ticket 进入 HTTP Authorization header 前校验它。 */
internal fun validateMobileWebUiContentGrant(
    grant: MobileWebUiContentGrant,
    target: MobileWebUiTarget,
) {
    validateMobileWebUiTarget(target)
    require(grant.targetKey == target.targetKey) { "WebUI content ticket target 不匹配" }
    require(grant.manifestDigest == target.manifestDigest) { "WebUI content ticket digest 不匹配" }
    require(grant.ticket.length in 1..MOBILE_WEB_UI_MAX_TICKET_CHARS) {
        "WebUI content ticket 长度无效"
    }
    require(grant.ticket.all { it.code in 0x21..0x7e }) {
        "WebUI content ticket 含空白或控制字符"
    }
    require(grant.expiresAt.length in 20..40) { "WebUI content ticket 过期时间长度无效" }
    try {
        // 服务端仍是过期权威；客户端只验证带 offset 的 ISO-8601 线格式，避免时钟偏差误拒绝。
        OffsetDateTime.parse(grant.expiresAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    } catch (_: java.time.format.DateTimeParseException) {
        throw IllegalArgumentException("WebUI content ticket 过期时间无效")
    }
}

internal data class MobileWebUiHttpRequest(
    val requestId: String,
    val path: String,
    val ticket: String,
    val blobSha256: String? = null,
    val byteLength: Long? = null,
    val offset: Long? = null,
)

internal data class MobileWebUiHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val contentLength: Long?,
    val contentRange: String?,
    val etag: String?,
    val contentDigest: String?,
    val representationDigest: String?,
)

internal fun mobileWebUiStrongEtag(digest: String): String = "\"$digest\""

internal fun mobileWebUiContentDigestMatches(header: String?, digest: String): Boolean {
    val encoded = header?.removePrefix("sha-256=:")?.removeSuffix(":") ?: return false
    if (header != "sha-256=:$encoded:") return false
    return try {
        java.util.Base64.getDecoder().decode(encoded).contentEquals(
            digest.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        )
    } catch (_: IllegalArgumentException) {
        false
    }
}

internal fun mobileWebUiSha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

internal const val MOBILE_WEB_UI_CAPABILITY = "mobile-webui-ota-v1"
internal const val MOBILE_WEB_UI_RELEASE_GET = "mobile.webui.release.get"
internal const val MOBILE_WEB_UI_CONTENT_PREPARE = "mobile.webui.content.prepare"
internal const val MOBILE_WEB_UI_RELEASE_CHANGED = "mobile.webui.release.changed"
internal const val MOBILE_WEB_UI_MANIFEST_PATH = "/mobile/webui/v1/manifest"
internal const val MOBILE_WEB_UI_BLOB_PATH = "/mobile/webui/v1/blob"
internal const val MOBILE_WEB_UI_MANIFEST_SCHEMA = 2
internal const val MOBILE_WEB_UI_BRIDGE_PROTOCOL = 2
internal const val MOBILE_WEB_UI_SNAPSHOT_PROTOCOL = 8
internal const val MOBILE_WEB_UI_NATIVE_BUILD = 48
internal const val MOBILE_WEB_UI_MAX_MANIFEST_BYTES = 1L * 1024 * 1024
internal const val MOBILE_WEB_UI_MAX_FILES = 2_048
internal const val MOBILE_WEB_UI_MAX_FILE_BYTES = 8L * 1024 * 1024
internal const val MOBILE_WEB_UI_MAX_UNPACKED_BYTES = 64L * 1024 * 1024
internal const val MOBILE_WEB_UI_MAX_TICKET_CHARS = 4096

internal val MOBILE_WEB_UI_SHA256 = Regex("^[0-9a-f]{64}$")
private val MOBILE_WEB_UI_COMMIT = Regex("^[0-9a-f]{40}$")
private val MOBILE_WEB_UI_GENERATION = Regex("^[0-9a-f]{64}$")
private val MOBILE_WEB_UI_TARGET = Regex("^[0-9a-f]{64}$")
private val MOBILE_WEB_UI_EPOCH = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
private val MOBILE_WEB_UI_MIME = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$")
private val MOBILE_WEB_UI_ALLOWED_TYPES = setOf(
    "application/json",
    "application/octet-stream",
    "application/wasm",
    "audio/mpeg",
    "audio/ogg",
    "audio/wav",
    "font/otf",
    "font/ttf",
    "font/woff",
    "font/woff2",
    "image/avif",
    "image/gif",
    "image/jpeg",
    "image/png",
    "image/svg+xml",
    "image/webp",
    "text/css",
    "text/html",
    "text/javascript",
    "text/plain",
    "video/mp4",
)
private val MOBILE_WEB_UI_SUFFIX_MIME = mapOf(
    ".avif" to "image/avif",
    ".bin" to "application/octet-stream",
    ".cjs" to "text/javascript",
    ".css" to "text/css",
    ".gif" to "image/gif",
    ".html" to "text/html",
    ".htm" to "text/html",
    ".jpeg" to "image/jpeg",
    ".jpg" to "image/jpeg",
    ".js" to "text/javascript",
    ".json" to "application/json",
    ".map" to "application/json",
    ".mjs" to "text/javascript",
    ".mp3" to "audio/mpeg",
    ".mp4" to "video/mp4",
    ".ogg" to "audio/ogg",
    ".otf" to "font/otf",
    ".png" to "image/png",
    ".svg" to "image/svg+xml",
    ".txt" to "text/plain",
    ".ttf" to "font/ttf",
    ".wav" to "audio/wav",
    ".wasm" to "application/wasm",
    ".webp" to "image/webp",
    ".woff" to "font/woff",
    ".woff2" to "font/woff2",
)
private val MOBILE_WEB_UI_FORBIDDEN_SUFFIXES = setOf(".dex", ".jar", ".so", ".apk", ".aab")

internal val MOBILE_WEB_UI_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}

/** 按严格的字段存在性和未知字段合同解码 OTA 发布 payload。 */
internal fun decodeMobileWebUiReleaseView(payload: JsonObject): MobileWebUiReleaseView {
    require("stable" in payload && "preview" in payload) {
        "WebUI release response 必须显式包含 stable 和 preview"
    }
    return MOBILE_WEB_UI_JSON.decodeFromJsonElement(MobileWebUiReleaseView.serializer(), payload)
}

/** 解码发布 hint，禁止部分或格式错误的 selection digest。 */
internal fun decodeMobileWebUiReleaseChangedPayload(payload: JsonObject): MobileWebUiReleaseChangedPayload {
    val decoded = MOBILE_WEB_UI_JSON.decodeFromJsonElement(
        MobileWebUiReleaseChangedPayload.serializer(),
        payload,
    )
    require(decoded.serverId.isNotBlank()) { "WebUI release hint server_id 无效" }
    require(MOBILE_WEB_UI_SHA256.matches(decoded.selectionDigest)) {
        "WebUI release hint selection_digest 无效"
    }
    return decoded
}

/** 在已认证协议边界校验发布目标。 */
internal fun validateMobileWebUiTarget(target: MobileWebUiTarget, expectedServerId: String? = null) {
    require(MOBILE_WEB_UI_TARGET.matches(target.targetKey)) { "WebUI target_key 无效" }
    require(MOBILE_WEB_UI_GENERATION.matches(target.generationId)) { "WebUI generation_id 无效" }
    require(MOBILE_WEB_UI_SHA256.matches(target.manifestDigest)) { "WebUI manifest digest 无效" }
    require(target.manifestSizeBytes in 1..MOBILE_WEB_UI_MAX_MANIFEST_BYTES) {
        "WebUI manifest 大小无效"
    }
    require(target.bridgeProtocolMin in 1..target.bridgeProtocolMax) {
        "WebUI bridge protocol 范围无效"
    }
    require(target.snapshotProtocolMin in 1..target.snapshotProtocolMax) {
        "WebUI snapshot protocol 范围无效"
    }
    require(target.minimumNativeBuild > 0) { "WebUI minimum_native_build 无效" }
    require(target.platforms.isNotEmpty() && target.platforms.size <= 64 &&
        target.platforms.distinct().size == target.platforms.size) {
        "WebUI platforms 无效"
    }
    require(target.platforms == target.platforms.sorted()) { "WebUI platforms 顺序无效" }
    require(target.platforms.all(::isMobileWebUiPlatformToken)) {
        "WebUI platforms 包含无效值"
    }
    expectedServerId?.let {
        require(target.targetKey == deriveMobileWebUiTargetKey(it, target.generationId, target.manifestDigest)) {
            "WebUI target_key 与 server/generation/manifest 不一致"
        }
    }
}

/** 校验发布视图，但不按 sequence 或墙上时钟排序。 */
internal fun validateMobileWebUiReleaseView(view: MobileWebUiReleaseView, expectedServerId: String) {
    require(view.serverId == expectedServerId) { "WebUI release server_id 不匹配" }
    require(MOBILE_WEB_UI_EPOCH.matches(view.releaseEpoch)) { "WebUI release_epoch 无效" }
    require(view.sequence >= 0) { "WebUI release sequence 无效" }
    require(MOBILE_WEB_UI_SHA256.matches(view.selectionDigest)) { "WebUI selection_digest 无效" }
    view.stable?.let { validateMobileWebUiTarget(it, expectedServerId) }
    view.preview?.let { validateMobileWebUiTarget(it, expectedServerId) }
    val expectedDigest = mobileWebUiSelectionDigest(
        expectedServerId,
        view.stable?.targetKey,
        view.preview?.targetKey,
    )
    require(view.selectionDigest == expectedDigest) { "WebUI selection_digest 不一致" }
}

/** 在写入任何文件前，校验 schema-2 manifest 及其可复现性证据。 */
internal fun validateMobileWebUiManifest(manifest: MobileWebUiManifest) {
    require(manifest.schemaVersion == MOBILE_WEB_UI_MANIFEST_SCHEMA) {
        "不支持的 WebUI manifest schema: ${manifest.schemaVersion}"
    }
    require(MOBILE_WEB_UI_GENERATION.matches(manifest.generationId)) { "WebUI generation_id 无效" }
    require(manifest.entrypoint == "mobile.html") { "WebUI entrypoint 不受支持" }
    require(manifest.files.isNotEmpty() && manifest.files.size <= MOBILE_WEB_UI_MAX_FILES) {
        "WebUI 文件数量无效"
    }
    require(manifest.files.zipWithNext().all { (left, right) -> compareUtf8(left.path, right.path) <= 0 }) {
        "WebUI files 必须按 path 排序"
    }
    require(manifest.fileCount == manifest.files.size) { "WebUI file_count 与清单不一致" }
    require(manifest.files.any { it.path == manifest.entrypoint }) {
        "WebUI entrypoint 不在文件清单中"
    }
    require(manifest.unpackedSizeBytes in 0..MOBILE_WEB_UI_MAX_UNPACKED_BYTES) {
        "WebUI 解包大小无效"
    }
    require(manifest.bridgeProtocolMin in 1..manifest.bridgeProtocolMax) {
        "WebUI bridge protocol 范围无效"
    }
    require(manifest.snapshotProtocolMin in 1..manifest.snapshotProtocolMax) {
        "WebUI snapshot protocol 范围无效"
    }
    require(manifest.minimumNativeBuild > 0) { "WebUI minimum_native_build 无效" }
    require(manifest.platforms.isNotEmpty() && manifest.platforms.size <= 64 &&
        manifest.platforms.distinct().size == manifest.platforms.size) {
        "WebUI platforms 无效"
    }
    require(manifest.platforms == manifest.platforms.sorted()) { "WebUI platforms 顺序无效" }
    require(manifest.platforms.contains("android")) { "WebUI manifest 不支持 Android" }
    require(manifest.platforms.all(::isMobileWebUiPlatformToken)) {
        "WebUI platforms 包含无效值"
    }
    requireBoundedToken(manifest.sourceRepository, 2048, "source_repository")
    require(MOBILE_WEB_UI_COMMIT.matches(manifest.sourceCommit)) { "WebUI source_commit 无效" }
    require(MOBILE_WEB_UI_COMMIT.matches(manifest.sourceTree)) { "WebUI source_tree 无效" }
    require(MOBILE_WEB_UI_SHA256.matches(manifest.inputDigest)) { "WebUI input_digest 无效" }
    require(MOBILE_WEB_UI_SHA256.matches(manifest.buildContextDigest)) {
        "WebUI build_context_digest 无效"
    }
    requireBoundedToken(manifest.builderIdentity.nodeVersion, 64, "builder_identity.node_version")
    requireBoundedToken(manifest.builderIdentity.npmVersion, 64, "builder_identity.npm_version")
    require(MOBILE_WEB_UI_SHA256.matches(manifest.builderIdentity.packageLockDigest)) {
        "WebUI package_lock_digest 无效"
    }
    require(MOBILE_WEB_UI_SHA256.matches(manifest.builderIdentity.buildScriptDigest)) {
        "WebUI build_script_digest 无效"
    }
    if (manifest.reproducible) {
        require(manifest.dirtyProvenance == null) { "reproducible WebUI 不得含 dirty provenance" }
    } else {
        require(manifest.dirtyProvenance != null) { "不可复现 WebUI 必须含 dirty provenance" }
    }
    manifest.dirtyProvenance?.let {
        require(MOBILE_WEB_UI_COMMIT.matches(it.baseCommit)) { "WebUI dirty base_commit 无效" }
        require(MOBILE_WEB_UI_SHA256.matches(it.trackedPatchDigest)) {
            "WebUI tracked_patch_digest 无效"
        }
        require(MOBILE_WEB_UI_SHA256.matches(it.untrackedTreeDigest)) {
            "WebUI untracked_tree_digest 无效"
        }
    }
    val seen = HashSet<String>(manifest.files.size)
    val folded = HashSet<String>(manifest.files.size)
    val digestMetadata = HashMap<String, Pair<Long, String>>(manifest.files.size)
    var total = 0L
    manifest.files.forEach { file ->
        validateMobileWebUiFile(file)
        val normalized = normalizeMobileWebUiPath(file.path)
        require(seen.add(normalized)) { "WebUI 文件路径重复: ${file.path}" }
        require(folded.add(normalized.casefold())) { "WebUI 文件路径大小写冲突: ${file.path}" }
        require(digestMetadata.putIfAbsent(file.sha256, file.sizeBytes to file.mime) in
            setOf(null, file.sizeBytes to file.mime)
        ) {
            "WebUI 同一 generation 的 digest size/MIME 冲突: ${file.sha256}"
        }
        total = Math.addExact(total, file.sizeBytes)
    }
    require(total == manifest.unpackedSizeBytes) { "WebUI 解包大小与清单不一致" }
    require(total <= MOBILE_WEB_UI_MAX_UNPACKED_BYTES) { "WebUI 解包大小超限" }
    val identity = mobileWebUiGenerationIdentityDigest(manifest)
    require(identity == manifest.generationId) { "WebUI generation_id 与 canonical manifest 不一致" }
}

internal fun validateMobileWebUiManifest(manifest: MobileWebUiManifest, target: MobileWebUiTarget) {
    validateMobileWebUiManifest(manifest)
    validateMobileWebUiTarget(target)
    require(manifest.generationId == target.generationId) { "WebUI generation_id 不匹配" }
    require(manifest.bridgeProtocolMin == target.bridgeProtocolMin && manifest.bridgeProtocolMax == target.bridgeProtocolMax) {
        "WebUI bridge protocol 与 descriptor 不匹配"
    }
    require(manifest.snapshotProtocolMin == target.snapshotProtocolMin && manifest.snapshotProtocolMax == target.snapshotProtocolMax) {
        "WebUI snapshot protocol 与 descriptor 不匹配"
    }
    require(manifest.minimumNativeBuild == target.minimumNativeBuild) {
        "WebUI minimum_native_build 不匹配"
    }
    require(manifest.platforms == target.platforms) { "WebUI platforms 与 descriptor 不匹配" }
}

/** 判断已验证 manifest 能否由当前原生 WebView 容器安全运行。 */
internal fun mobileWebUiManifestSupportsNative(
    manifest: MobileWebUiManifest,
    nativeBuild: Int,
): Boolean =
    nativeBuild >= manifest.minimumNativeBuild &&
        MOBILE_WEB_UI_BRIDGE_PROTOCOL in manifest.bridgeProtocolMin..manifest.bridgeProtocolMax &&
        MOBILE_WEB_UI_SNAPSHOT_PROTOCOL in manifest.snapshotProtocolMin..manifest.snapshotProtocolMax &&
        "android" in manifest.platforms

internal fun validateMobileWebUiFile(file: MobileWebUiManifestFile) {
    require(normalizeMobileWebUiPath(file.path) == file.path) { "WebUI 文件路径必须是 NFC 规范化路径" }
    require(MOBILE_WEB_UI_SHA256.matches(file.sha256)) { "WebUI 文件摘要无效: ${file.path}" }
    require(file.sizeBytes in 0..MOBILE_WEB_UI_MAX_FILE_BYTES) { "WebUI 文件大小无效: ${file.path}" }
    require(MOBILE_WEB_UI_MIME.matches(file.mime)) { "WebUI MIME 无效: ${file.mime}" }
    require(file.mime in MOBILE_WEB_UI_ALLOWED_TYPES) { "WebUI MIME 不在允许列表: ${file.mime}" }
    val suffix = file.path.substringAfterLast('.', "").let {
        if (it.isEmpty()) "" else ".${it.lowercase(Locale.ROOT)}"
    }
    require((MOBILE_WEB_UI_SUFFIX_MIME[suffix] ?: "application/octet-stream") == file.mime) {
        "WebUI 文件后缀与 MIME 不一致: ${file.path} -> ${file.mime}"
    }
    require(MOBILE_WEB_UI_FORBIDDEN_SUFFIXES.none { file.path.lowercase(Locale.ROOT).endsWith(it) }) {
        "WebUI 文件类型禁止进入 WebUI: ${file.path}"
    }
}

private fun isMobileWebUiPlatformToken(value: String): Boolean =
    value.length in 1..64 && value.all {
        it.code < 128 && (it.isLetterOrDigit() || it in "-_.")
    }

internal fun normalizeMobileWebUiPath(path: String): String {
    require(path.isNotBlank() && path.toByteArray(Charsets.UTF_8).size <= 512) { "WebUI 文件路径无效" }
    require(path == path.trim()) { "WebUI 文件路径不得包含首尾空白" }
    require(path.none { it == '\u0000' || it == '\\' }) { "WebUI 文件路径包含非法字符" }
    val normalized = Normalizer.normalize(path, Normalizer.Form.NFC)
    require(normalized == path) { "WebUI 文件路径必须使用 NFC" }
    val segments = normalized.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "WebUI 文件路径分段无效" }
    require(segments.all { segment ->
        val bytes = segment.toByteArray(Charsets.UTF_8)
        bytes.size in 1..128 && segment.all { char -> char.isLetterOrDigit() || char in "._-" } &&
            segment.all { char -> char.code < 128 }
    }) {
        "WebUI 文件路径包含控制字符"
    }
    require(!normalized.startsWith('/')) { "WebUI 文件路径不得为绝对路径" }
    return normalized
}

internal fun mobileWebUiPathKey(path: String): String = normalizeMobileWebUiPath(path).casefold()

internal fun deriveMobileWebUiTargetKey(serverId: String, generationId: String, manifestDigest: String): String =
    sha256CanonicalJson(
        mapOf(
            "server_id" to JsonPrimitive(serverId),
            "generation_id" to JsonPrimitive(generationId),
            "manifest_digest" to JsonPrimitive(manifestDigest),
        ),
    )

internal fun mobileWebUiSelectionDigest(
    serverId: String,
    stableTargetKey: String?,
    previewTargetKey: String?,
): String = sha256CanonicalJson(
    mapOf(
        "server_id" to JsonPrimitive(serverId),
        "stable_target_key" to (stableTargetKey?.let(::JsonPrimitive) ?: JsonNull),
        "preview_target_key" to (previewTargetKey?.let(::JsonPrimitive) ?: JsonNull),
    ),
)

internal fun mobileWebUiManifestBytes(manifest: MobileWebUiManifest): ByteArray =
    canonicalJson(MOBILE_WEB_UI_JSON.encodeToJsonElement(manifest))

internal fun mobileWebUiGenerationIdentityDigest(manifest: MobileWebUiManifest): String {
    val element = MOBILE_WEB_UI_JSON.encodeToJsonElement(manifest).jsonObject.toMutableMap()
    element.remove("generation_id")
    return sha256(canonicalJson(JsonObject(element)))
}

internal fun mobileWebUiManifestDigest(manifest: MobileWebUiManifest): String =
    sha256(mobileWebUiManifestBytes(manifest))

private fun sha256CanonicalJson(values: Map<String, JsonElement>): String = sha256(
    canonicalJson(JsonObject(values)),
)

private fun canonicalJson(element: JsonElement): ByteArray {
    val output = StringBuilder()
    appendCanonical(output, element)
    return output.toString().toByteArray(Charsets.UTF_8)
}

private fun appendCanonical(output: StringBuilder, element: JsonElement) {
    when (element) {
        is JsonObject -> {
            output.append('{')
            element.keys.sorted().forEachIndexed { index, key ->
                if (index > 0) output.append(',')
                output.append(JsonPrimitive(key).toString()).append(':')
                appendCanonical(output, element.getValue(key))
            }
            output.append('}')
        }
        is JsonArray -> {
            output.append('[')
            element.forEachIndexed { index, item ->
                if (index > 0) output.append(',')
                appendCanonical(output, item)
            }
            output.append(']')
        }
        is JsonPrimitive -> output.append(element.toString())
    }
}

private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun requireBoundedToken(value: String, maxLength: Int, label: String) {
    require(value.isNotBlank() && value.length <= maxLength && value.none { it.isWhitespace() }) {
        "WebUI $label 无效"
    }
}

private fun String.casefold(): String = lowercase(Locale.ROOT)

private fun compareUtf8(left: String, right: String): Int {
    val leftBytes = left.toByteArray(Charsets.UTF_8)
    val rightBytes = right.toByteArray(Charsets.UTF_8)
    val shared = minOf(leftBytes.size, rightBytes.size)
    for (index in 0 until shared) {
        val comparison = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return leftBytes.size - rightBytes.size
}
