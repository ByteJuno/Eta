package io.github.bytejuno.theta.ui.app

internal fun isSharedImageType(
    resolverMimeType: String?,
    path: String,
    intentMimeType: String?,
): Boolean {
    val resolverType = resolverMimeType.normalizedShareMimeType()
    if (resolverType != null && resolverType != "*/*" && resolverType != "application/octet-stream") {
        return resolverType.startsWith("image/")
    }
    val extension = path.substringAfterLast('.', "").lowercase()
    if (extension in SHARED_IMAGE_EXTENSIONS) return true
    // ACTION_SEND_MULTIPLE 的 image/* 同样适用于无后缀 URI；具体的单文件类型优先。
    return intentMimeType.normalizedShareMimeType()?.startsWith("image/") == true
}

private fun String?.normalizedShareMimeType(): String? =
    this?.substringBefore(';')?.trim()?.lowercase()?.takeIf(String::isNotBlank)

private val SHARED_IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
)
