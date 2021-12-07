package de.rki.coronawarnapp.dccticketing.core.allowlist.filtering

import de.rki.coronawarnapp.dccticketing.core.allowlist.repo.DccTicketingAllowListRepository
import de.rki.coronawarnapp.dccticketing.core.transaction.DccJWK
import javax.inject.Inject

class DccTicketingJwkFilter @Inject constructor(
    private val dccTicketingAllowListRepository: DccTicketingAllowListRepository
) {

    suspend fun filter(jwkSet: Set<DccJWK>): DccJwkFilteringResult {
        val container = dccTicketingAllowListRepository.refresh()
        val validationServiceAllowList = container.validationServiceAllowList
        val serviceProviderAllowList = container.serviceProviderAllowList
        // TODO
        return DccJwkFilteringResult(
            filteredAllowlist = emptySet(),
            filteredJwkSet = jwkSet
        )
    }
}
