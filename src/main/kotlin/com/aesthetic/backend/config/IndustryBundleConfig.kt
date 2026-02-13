package com.aesthetic.backend.config

import com.aesthetic.backend.domain.subscription.FeatureModule
import com.aesthetic.backend.domain.tenant.BusinessType
import org.springframework.stereotype.Component

@Component
class IndustryBundleConfig {

    private val bundles: Map<BusinessType, List<FeatureModule>> = mapOf(
        BusinessType.BEAUTY_CLINIC to listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.GALLERY,
            FeatureModule.PRODUCTS,
            FeatureModule.REVIEWS,
            FeatureModule.PATIENT_RECORDS,
            FeatureModule.BLOG,
            FeatureModule.NOTIFICATIONS,
            FeatureModule.CLIENT_NOTES
        ),
        BusinessType.DENTAL_CLINIC to listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.PATIENT_RECORDS,
            FeatureModule.REVIEWS,
            FeatureModule.CONTACT_MESSAGES,
            FeatureModule.NOTIFICATIONS,
            FeatureModule.CLIENT_NOTES
        ),
        BusinessType.BARBER_SHOP to listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.GALLERY,
            FeatureModule.REVIEWS,
            FeatureModule.PRODUCTS,
            FeatureModule.NOTIFICATIONS
        ),
        BusinessType.HAIR_SALON to listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.GALLERY,
            FeatureModule.REVIEWS,
            FeatureModule.PRODUCTS,
            FeatureModule.BLOG,
            FeatureModule.NOTIFICATIONS
        ),
        BusinessType.DIETITIAN to listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.PATIENT_RECORDS,
            FeatureModule.REVIEWS,
            FeatureModule.BLOG,
            FeatureModule.NOTIFICATIONS,
            FeatureModule.CLIENT_NOTES
        ),
        BusinessType.PHYSIOTHERAPIST to listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.PATIENT_RECORDS,
            FeatureModule.REVIEWS,
            FeatureModule.NOTIFICATIONS,
            FeatureModule.CLIENT_NOTES
        ),
        BusinessType.MASSAGE_SALON to listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.GALLERY,
            FeatureModule.REVIEWS,
            FeatureModule.PRODUCTS,
            FeatureModule.NOTIFICATIONS
        ),
        BusinessType.VETERINARY to listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.PATIENT_RECORDS,
            FeatureModule.REVIEWS,
            FeatureModule.CONTACT_MESSAGES,
            FeatureModule.NOTIFICATIONS,
            FeatureModule.CLIENT_NOTES
        ),
        BusinessType.GENERAL to listOf(
            FeatureModule.APPOINTMENTS,
            FeatureModule.REVIEWS,
            FeatureModule.CONTACT_MESSAGES,
            FeatureModule.NOTIFICATIONS
        )
    )

    fun getModulesForBusinessType(businessType: BusinessType): List<FeatureModule> {
        return bundles[businessType] ?: bundles[BusinessType.GENERAL]!!
    }
}
