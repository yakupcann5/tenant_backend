package com.aesthetic.backend.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PasswordValidatorTest {

    private val validator = PasswordValidator()

    @Test
    fun `should accept strong password`() {
        assertTrue(validator.isValid("StrongPass1", null))
        assertTrue(validator.isValid("MySecure99", null))
        assertTrue(validator.isValid("Test1234Abc", null))
        assertTrue(validator.isValid("C0mplexPwd", null))
    }

    @Test
    fun `should reject short password`() {
        assertFalse(validator.isValid("Abc1", null))
        assertFalse(validator.isValid("Ab1defg", null))
    }

    @Test
    fun `should reject password without uppercase`() {
        assertFalse(validator.isValid("lowercase1", null))
        assertFalse(validator.isValid("alllower99", null))
    }

    @Test
    fun `should reject password without lowercase`() {
        assertFalse(validator.isValid("UPPERCASE1", null))
        assertFalse(validator.isValid("ALLUPPER99", null))
    }

    @Test
    fun `should reject password without digit`() {
        assertFalse(validator.isValid("NoDigitsHere", null))
        assertFalse(validator.isValid("AbcDefGhi", null))
    }

    @Test
    fun `should reject common passwords`() {
        assertFalse(validator.isValid("Password1", null))
        assertFalse(validator.isValid("Admin123", null))
        assertFalse(validator.isValid("Qwerty123", null))
        assertFalse(validator.isValid("Welcome1", null))
    }

    @Test
    fun `should accept null value`() {
        assertTrue(validator.isValid(null, null))
    }

    @Test
    fun `should accept exactly 8 character password`() {
        assertTrue(validator.isValid("Abcdef1x", null))
    }
}
