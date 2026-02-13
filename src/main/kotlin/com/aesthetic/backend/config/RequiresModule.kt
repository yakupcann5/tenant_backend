package com.aesthetic.backend.config

import com.aesthetic.backend.domain.subscription.FeatureModule

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresModule(val value: FeatureModule)
