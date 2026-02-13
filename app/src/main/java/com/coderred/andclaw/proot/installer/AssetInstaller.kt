package com.coderred.andclaw.proot.installer

interface AssetInstaller<T> {
    suspend fun install(
        spec: T,
        onProgress: (entries: Int) -> Unit = {},
    )
}

