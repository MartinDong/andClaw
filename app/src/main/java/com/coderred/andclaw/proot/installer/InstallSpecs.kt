package com.coderred.andclaw.proot.installer

import java.io.File

data class TarInstallSpec(
    val assetName: String,
    val cacheDir: File,
    val destinationDir: File,
    val permissionRootDir: File,
    val stripComponents: Int = 0,
)

data class DirectoryInstallSpec(
    val assetPath: String,
    val destinationDir: File,
    val permissionRootDir: File? = null,
    val permissionKey: String? = null,
    val cleanRelativePaths: List<String> = emptyList(),
)
