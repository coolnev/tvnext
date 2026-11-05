package com.tvnext.app.core.licensing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StaticLicenseProvider(
    initialStatus: LicenseStatus = LicenseStatus.FREE,
) : LicenseProvider {

    private val _status = MutableStateFlow(initialStatus)
    override val status: StateFlow<LicenseStatus> = _status.asStateFlow()
}
