package com.aesthetic.backend.usecase

interface StorageProvider {
    fun upload(bytes: ByteArray, path: String): String
    fun delete(path: String)
}
