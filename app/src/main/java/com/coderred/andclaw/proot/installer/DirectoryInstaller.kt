package com.coderred.andclaw.proot.installer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DirectoryInstaller(
    private val context: Context,
    private val executableManifest: ExecutableManifest,
) : AssetInstaller<DirectoryInstallSpec> {
    override suspend fun install(
        spec: DirectoryInstallSpec,
        onProgress: (entries: Int) -> Unit,
    ) = withContext<Unit>(Dispatchers.IO) {
        spec.cleanRelativePaths.forEach { relative ->
            File(spec.destinationDir, relative).deleteRecursively()
        }
        copyAssetDirectory(spec.assetPath, spec.destinationDir, onProgress)
        val permissionKey = spec.permissionKey
        val root = spec.permissionRootDir
        if (!permissionKey.isNullOrBlank() && root != null) {
            executableManifest.apply(permissionKey, root)
        }
        Unit
    }

    private fun copyAssetDirectory(assetPath: String, destinationDir: File, onProgress: (entries: Int) -> Unit) {
        var copied = 0
        copyRecursive(assetPath, destinationDir) {
            copied++
            onProgress(copied)
        }
    }

    private fun copyRecursive(assetPath: String, destinationDir: File, onCopied: () -> Unit) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destinationDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destinationDir.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            onCopied()
            return
        }

        destinationDir.mkdirs()
        children.forEach { child ->
            val childAssetPath = if (assetPath.isBlank()) child else "$assetPath/$child"
            val childDest = File(destinationDir, child)
            copyRecursive(childAssetPath, childDest, onCopied)
        }
    }
}
