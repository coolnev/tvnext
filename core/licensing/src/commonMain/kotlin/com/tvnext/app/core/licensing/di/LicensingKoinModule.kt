package com.tvnext.app.core.licensing.di

import com.tvnext.app.core.licensing.DefaultPremiumGate
import com.tvnext.app.core.licensing.LicenseProvider
import com.tvnext.app.core.licensing.PremiumGate
import com.tvnext.app.core.licensing.StaticLicenseProvider
import org.koin.core.module.Module
import org.koin.dsl.module

val licensingKoinModule: Module = module {
    single<LicenseProvider> { StaticLicenseProvider() }
    single<PremiumGate> { DefaultPremiumGate(get()) }
}
