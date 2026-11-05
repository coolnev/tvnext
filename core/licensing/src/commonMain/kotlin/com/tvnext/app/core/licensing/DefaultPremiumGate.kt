package com.tvnext.app.core.licensing

class DefaultPremiumGate(
    private val provider: LicenseProvider,
) : PremiumGate {

    override val canAccessPremiumFeatures: Boolean
        get() = provider.status.value != LicenseStatus.FREE
}
