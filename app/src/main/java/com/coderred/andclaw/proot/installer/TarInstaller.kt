package com.coderred.andclaw.proot.installer

import android.content.Context
import com.coderred.andclaw.proot.ArchiveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TarInstaller(
    private val context: Context,
    private val executableManifest: ExecutableManifest,
) : AssetInstaller<TarInstallSpec> {
    override suspend fun install(
        spec: TarInstallSpec,
        onProgress: (entries: Int) -> Unit,
    ) = withContext<Unit>(Dispatchers.IO) {
        val cacheFile = File(spec.cacheDir, spec.assetName)
        copyAssetToFile(spec.assetName, cacheFile)

        try {
            ArchiveUtils.extractTarGz(
                tarGzFile = cacheFile,
                destDir = spec.destinationDir,
                stripComponents = spec.stripComponents,
                onProgress = onProgress,
            )
            executableManifest.apply(spec.assetName, spec.permissionRootDir)
        } finally {
            cacheFile.delete()
        }
        Unit
    }

    private fun copyAssetToFile(assetName: String, destFile: File): Long {
        destFile.parentFile?.mkdirs()
        var total = 0L
        context.assets.open(assetName).buffered(65536).use { input ->
            destFile.outputStream().buffered(65536).use { output ->
                val buffer = ByteArray(65536)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    total += read
                }
                output.flush()
            }
        }
        return total
    }
}
