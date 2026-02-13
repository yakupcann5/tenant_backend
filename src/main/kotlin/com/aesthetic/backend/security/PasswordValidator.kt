package com.aesthetic.backend.security

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [PasswordValidator::class])
annotation class Password(
    val message: String = "Şifre en az 8 karakter, büyük harf, küçük harf ve rakam içermelidir",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class PasswordValidator : ConstraintValidator<Password, String> {

    private val commonPasswords = setOf(
        "password", "12345678", "123456789", "qwerty123", "abc12345",
        "password1", "iloveyou", "admin123", "welcome1", "monkey123"
    )

    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        if (value == null) return true
        if (value.length < 8) return false
        if (!value.any { it.isUpperCase() }) return false
        if (!value.any { it.isLowerCase() }) return false
        if (!value.any { it.isDigit() }) return false
        if (value.lowercase() in commonPasswords) return false
        return true
    }
}
