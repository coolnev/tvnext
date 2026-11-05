package com.tvnext.app.core.licensing

import kotlinx.coroutines.flow.StateFlow

interface LicenseProvider {
    val status: StateFlow<LicenseStatus>
}
