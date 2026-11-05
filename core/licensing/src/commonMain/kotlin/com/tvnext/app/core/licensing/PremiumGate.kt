package com.tvnext.app.core.licensing

import com.tvnext.app.core.common.mvi.MviComponent
import com.tvnext.app.core.common.mvi.MviEffect
import com.tvnext.app.core.common.mvi.MviIntent
import com.tvnext.app.core.common.mvi.MviState

interface PremiumGate {
    val canAccessPremiumFeatures: Boolean
}

data class PremiumGateState(
    val status: LicenseStatus = LicenseStatus.FREE,
) : MviState

sealed interface PremiumGateIntent : MviIntent {
    data object Refresh : PremiumGateIntent
}

sealed interface PremiumGateEffect : MviEffect {
    data object PremiumUnlocked : PremiumGateEffect
}
