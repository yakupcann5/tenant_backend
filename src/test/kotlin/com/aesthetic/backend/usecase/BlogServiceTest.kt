package com.aesthetic.backend.usecase

import com.aesthetic.backend.domain.blog.BlogPost
import com.aesthetic.backend.domain.user.Role
import com.aesthetic.backend.domain.user.User
import com.aesthetic.backend.dto.request.CreateBlogPostRequest
import com.aesthetic.backend.dto.request.UpdateBlogPostRequest
import com.aesthetic.backend.exception.ResourceNotFoundException
import com.aesthetic.backend.repository.BlogPostRepository
import com.aesthetic.backend.repository.UserRepository
import com.aesthetic.backend.security.UserPrincipal
import com.aesthetic.backend.tenant.TenantContext
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.util.*

@ExtendWith(MockKExtension::class)
class BlogServiceTest {

    @MockK
    private lateinit var blogPostRepository: BlogPostRepository

    @MockK
    private lateinit var entityManager: EntityManager

    @MockK
    private lateinit var userRepository: UserRepository

    private lateinit var blogService: BlogService

    private val tenantId = "test-tenant-id"

    @BeforeEach
    fun setUp() {
        blogService = BlogService(blogPostRepository, entityManager, userRepository)
        TenantContext.setTenantId(tenantId)
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
    }

    @Test
    fun `create should save blog post with generated slug`() {
        val author = createUser("user-1", "Admin", "User")
        val principal = createPrincipal("user-1")
        every { userRepository.findById("user-1") } returns Optional.of(author)
        every { blogPostRepository.findBySlugAndTenantId("test-blog-post", tenantId) } returns null
        every { blogPostRepository.save(any()) } answers {
            (firstArg() as BlogPost).apply {
                val idField = BlogPost::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "blog-1")
            }
        }

        val request = CreateBlogPostRequest(
            title = "Test Blog Post",
            content = "This is the content",
            summary = "A summary",
            tags = listOf("beauty", "tips")
        )
        val result = blogService.create(request, principal)

        assertEquals("blog-1", result.id)
        assertEquals("test-blog-post", result.slug)
        assertEquals("Test Blog Post", result.title)
        assertEquals("This is the content", result.content)
        assertEquals("A summary", result.summary)
        assertEquals(listOf("beauty", "tips"), result.tags)
        assertEquals("Admin User", result.authorName)
        assertEquals("1 dk okuma", result.readTime)
        assertFalse(result.isPublished)
    }

    @Test
    fun `create should generate unique slug when duplicate exists`() {
        val author = createUser("user-1", "Admin", "User")
        val principal = createPrincipal("user-1")
        every { userRepository.findById("user-1") } returns Optional.of(author)
        every { blogPostRepository.findBySlugAndTenantId("test-blog-post", tenantId) } returns createBlogPost("existing-1")
        every { blogPostRepository.findBySlugAndTenantId("test-blog-post-1", tenantId) } returns null
        every { blogPostRepository.save(any()) } answers {
            (firstArg() as BlogPost).apply {
                val idField = BlogPost::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.set(this, "blog-2")
            }
        }

        val request = CreateBlogPostRequest(
            title = "Test Blog Post",
            content = "Content"
        )
        val result = blogService.create(request, principal)

        assertEquals("test-blog-post-1", result.slug)
    }

    @Test
    fun `create should throw when user not found`() {
        val principal = createPrincipal("nonexistent")
        every { userRepository.findById("nonexistent") } returns Optional.empty()

        val request = CreateBlogPostRequest(title = "Test", content = "Content")

        assertThrows<ResourceNotFoundException> {
            blogService.create(request, principal)
        }
    }

    @Test
    fun `update should update title, slug, and tags`() {
        val post = createBlogPost("blog-1")
        every { blogPostRepository.findById("blog-1") } returns Optional.of(post)
        every { blogPostRepository.findBySlugAndTenantId("updated-title", tenantId) } returns null
        every { blogPostRepository.save(any()) } answers { firstArg() }

        val request = UpdateBlogPostRequest(
            title = "Updated Title",
            tags = listOf("new-tag", "another-tag")
        )
        val result = blogService.update("blog-1", request)

        assertEquals("Updated Title", result.title)
        assertEquals("updated-title", result.slug)
        assertEquals(listOf("new-tag", "another-tag"), result.tags)
    }

    @Test
    fun `update should update only provided fields`() {
        val post = createBlogPost("blog-1")
        every { blogPostRepository.findById("blog-1") } returns Optional.of(post)
        every { blogPostRepository.save(any()) } answers { firstArg() }

        val request = UpdateBlogPostRequest(summary = "New summary")
        val result = blogService.update("blog-1", request)

        assertEquals("Test Blog Post", result.title)
        assertEquals("New summary", result.summary)
    }

    @Test
    fun `update should throw when blog post not found`() {
        every { blogPostRepository.findById("nonexistent") } returns Optional.empty()

        val request = UpdateBlogPostRequest(title = "Updated")

        assertThrows<ResourceNotFoundException> {
            blogService.update("nonexistent", request)
        }
    }

    @Test
    fun `delete should remove blog post`() {
        val post = createBlogPost("blog-1")
        every { blogPostRepository.findById("blog-1") } returns Optional.of(post)
        every { blogPostRepository.delete(post) } just Runs

        blogService.delete("blog-1")

        verify { blogPostRepository.delete(post) }
    }

    @Test
    fun `delete should throw when blog post not found`() {
        every { blogPostRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            blogService.delete("nonexistent")
        }
    }

    @Test
    fun `listPublished should return only published posts`() {
        val pageable = PageRequest.of(0, 20)
        val publishedPost = createBlogPost("blog-1").apply { isPublished = true }
        val page = PageImpl(listOf(publishedPost), pageable, 1)
        every { blogPostRepository.findAllByTenantIdAndIsPublishedTrue(tenantId, pageable) } returns page

        val result = blogService.listPublished(pageable)

        assertEquals(1, result.data.size)
        assertTrue(result.data[0].isPublished)
    }

    @Test
    fun `togglePublish should toggle from unpublished to published and set publishedAt`() {
        val post = createBlogPost("blog-1").apply { isPublished = false }
        every { blogPostRepository.findById("blog-1") } returns Optional.of(post)
        every { blogPostRepository.save(any()) } answers { firstArg() }

        val result = blogService.togglePublish("blog-1")

        assertTrue(result.isPublished)
        assertNotNull(result.publishedAt)
    }

    @Test
    fun `togglePublish should toggle from published to unpublished`() {
        val post = createBlogPost("blog-1").apply { isPublished = true }
        every { blogPostRepository.findById("blog-1") } returns Optional.of(post)
        every { blogPostRepository.save(any()) } answers { firstArg() }

        val result = blogService.togglePublish("blog-1")

        assertFalse(result.isPublished)
    }

    @Test
    fun `togglePublish should throw when blog post not found`() {
        every { blogPostRepository.findById("nonexistent") } returns Optional.empty()

        assertThrows<ResourceNotFoundException> {
            blogService.togglePublish("nonexistent")
        }
    }

    @Test
    fun `readTime should compute based on word count`() {
        val longContent = (1..600).joinToString(" ") { "word" }
        val post = createBlogPost("blog-1").apply { content = longContent }
        every { blogPostRepository.findById("blog-1") } returns Optional.of(post)
        every { blogPostRepository.save(any()) } answers { firstArg() }

        val request = UpdateBlogPostRequest(summary = "Updated")
        val result = blogService.update("blog-1", request)

        assertEquals("3 dk okuma", result.readTime)
    }

    @Test
    fun `readTime should strip HTML tags before counting`() {
        val htmlContent = "<p>Hello</p> <strong>world</strong> <a href='#'>click</a> here now"
        val post = createBlogPost("blog-1").apply { content = htmlContent }
        every { blogPostRepository.findById("blog-1") } returns Optional.of(post)
        every { blogPostRepository.save(any()) } answers { firstArg() }

        val request = UpdateBlogPostRequest(summary = "Updated")
        val result = blogService.update("blog-1", request)

        assertEquals("1 dk okuma", result.readTime)
    }

    @Test
    fun `getBySlug should return blog post`() {
        val post = createBlogPost("blog-1")
        every { blogPostRepository.findBySlugAndTenantId("test-blog-post", tenantId) } returns post

        val result = blogService.getBySlug("test-blog-post")

        assertEquals("blog-1", result.id)
        assertEquals("test-blog-post", result.slug)
    }

    @Test
    fun `getBySlug should throw when not found`() {
        every { blogPostRepository.findBySlugAndTenantId("nonexistent", tenantId) } returns null

        assertThrows<ResourceNotFoundException> {
            blogService.getBySlug("nonexistent")
        }
    }

    private fun createBlogPost(id: String = "blog-1"): BlogPost {
        return BlogPost(
            id = id,
            slug = "test-blog-post",
            title = "Test Blog Post",
            summary = "A summary",
            content = "This is the content",
            authorName = "Admin User",
            tags = mutableListOf("beauty")
        ).apply { tenantId = this@BlogServiceTest.tenantId }
    }

    private fun createUser(id: String, firstName: String, lastName: String): User {
        return User(
            id = id,
            firstName = firstName,
            lastName = lastName,
            email = "${firstName.lowercase()}@example.com",
            role = Role.TENANT_ADMIN
        ).apply { tenantId = this@BlogServiceTest.tenantId }
    }

    private fun createPrincipal(userId: String): UserPrincipal {
        return UserPrincipal(
            id = userId,
            email = "admin@example.com",
            tenantId = tenantId,
            role = Role.TENANT_ADMIN,
            passwordHash = "hashed"
        )
    }
}
