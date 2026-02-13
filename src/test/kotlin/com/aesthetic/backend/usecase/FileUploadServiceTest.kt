package com.aesthetic.backend.usecase

import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.web.multipart.MultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@ExtendWith(MockKExtension::class)
class FileUploadServiceTest {

    @MockK
    private lateinit var storageProvider: StorageProvider

    private lateinit var fileUploadService: FileUploadService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        fileUploadService = FileUploadService(storageProvider)
    }

    @Test
    fun `upload should return URL on successful upload`() {
        val imageBytes = createValidImageBytes()
        val file = mockMultipartFile("photo.jpg", imageBytes)

        every { storageProvider.upload(any(), any()) } returns "https://cdn.example.com/test-tenant-id/uploads/photo.jpg"

        val result = fileUploadService.upload(file, tenantId)

        assertNotNull(result)
        assertTrue(result.startsWith("https://"))
        verify { storageProvider.upload(imageBytes, match { it.startsWith("$tenantId/uploads/") && it.endsWith(".jpg") }) }
    }

    @Test
    fun `upload should throw when file size exceeds 5MB`() {
        val largeBytes = ByteArray(6 * 1024 * 1024) // 6MB
        val file = mockk<MultipartFile>()
        every { file.size } returns largeBytes.size.toLong()

        val exception = assertThrows<IllegalArgumentException> {
            fileUploadService.upload(file, tenantId)
        }

        assertEquals("Dosya boyutu 5MB'ı aşamaz", exception.message)
        verify(exactly = 0) { storageProvider.upload(any(), any()) }
    }

    @Test
    fun `upload should throw when file extension is not allowed`() {
        val file = mockk<MultipartFile>()
        every { file.size } returns 1024L
        every { file.originalFilename } returns "document.pdf"

        val exception = assertThrows<IllegalArgumentException> {
            fileUploadService.upload(file, tenantId)
        }

        assertTrue(exception.message!!.contains("Desteklenmeyen dosya formatı"))
        verify(exactly = 0) { storageProvider.upload(any(), any()) }
    }

    @Test
    fun `upload should throw when MIME type is not image`() {
        // Create a text file disguised as .jpg
        val textBytes = "This is not an image".toByteArray()
        val file = mockk<MultipartFile>()
        every { file.size } returns textBytes.size.toLong()
        every { file.originalFilename } returns "fake.jpg"
        every { file.bytes } returns textBytes

        val exception = assertThrows<IllegalArgumentException> {
            fileUploadService.upload(file, tenantId)
        }

        assertTrue(exception.message!!.contains("Dosya içeriği resim değil"))
        verify(exactly = 0) { storageProvider.upload(any(), any()) }
    }

    @Test
    fun `upload should throw when extension is null`() {
        val file = mockk<MultipartFile>()
        every { file.size } returns 1024L
        every { file.originalFilename } returns null

        val exception = assertThrows<IllegalArgumentException> {
            fileUploadService.upload(file, tenantId)
        }

        assertTrue(exception.message!!.contains("Desteklenmeyen dosya formatı"))
    }

    private fun createValidImageBytes(): ByteArray {
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", outputStream)
        return outputStream.toByteArray()
    }

    private fun mockMultipartFile(filename: String, content: ByteArray): MultipartFile {
        val file = mockk<MultipartFile>()
        every { file.size } returns content.size.toLong()
        every { file.originalFilename } returns filename
        every { file.bytes } returns content
        every { file.inputStream } returns ByteArrayInputStream(content)
        return file
    }
}
