package com.aesthetic.backend.usecase

import org.apache.tika.Tika
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.*
import javax.imageio.ImageIO

@Service
class FileUploadService(
    private val storageProvider: StorageProvider
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024L // 5MB
        private const val MAX_IMAGE_DIMENSION = 4096
        private val ALLOWED_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "gif")
    }

    fun upload(file: MultipartFile, tenantId: String): String {
        // 1. Size check
        require(file.size <= MAX_FILE_SIZE) { "Dosya boyutu 5MB'ı aşamaz" }

        // 2. Extension whitelist
        val ext = file.originalFilename?.substringAfterLast('.')?.lowercase()
        require(ext in ALLOWED_EXTENSIONS) { "Desteklenmeyen dosya formatı: $ext" }

        // 3. Tika MIME detection
        val detectedMime = Tika().detect(file.bytes)
        require(detectedMime.startsWith("image/")) { "Dosya içeriği resim değil: $detectedMime" }

        // 4. ImageIO dimension check
        val image = ImageIO.read(file.inputStream)
        require(image != null) { "Geçersiz resim dosyası" }
        require(image.width <= MAX_IMAGE_DIMENSION && image.height <= MAX_IMAGE_DIMENSION) {
            "Resim boyutu çok büyük: ${image.width}x${image.height} (max: ${MAX_IMAGE_DIMENSION}x${MAX_IMAGE_DIMENSION})"
        }

        // 5. UUID filename + tenant-scoped path
        val filename = "${UUID.randomUUID()}.$ext"
        val path = "$tenantId/uploads/$filename"

        val url = storageProvider.upload(file.bytes, path)
        logger.debug("File uploaded: tenant={}, path={}", tenantId, path)
        return url
    }
}
