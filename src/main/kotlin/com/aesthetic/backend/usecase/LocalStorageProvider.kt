package com.aesthetic.backend.usecase

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Service
@ConditionalOnProperty(name = ["storage.provider"], havingValue = "local", matchIfMissing = true)
class LocalStorageProvider(
    @Value("\${storage.local.base-path:./uploads}") private val basePath: String,
    @Value("\${storage.local.base-url:http://localhost:8080/uploads}") private val baseUrl: String
) : StorageProvider {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun upload(bytes: ByteArray, path: String): String {
        val fullPath: Path = Paths.get(basePath, path)
        Files.createDirectories(fullPath.parent)
        Files.write(fullPath, bytes)
        logger.debug("File uploaded: {}", fullPath)
        return "$baseUrl/$path"
    }

    override fun delete(path: String) {
        val fullPath: Path = Paths.get(basePath, path)
        if (Files.exists(fullPath)) {
            Files.delete(fullPath)
            logger.debug("File deleted: {}", fullPath)
        }
    }
}
