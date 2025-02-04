package de.rki.coronawarnapp.covidcertificate.common.certificate

import dagger.Reusable
import de.rki.coronawarnapp.covidcertificate.common.repository.CertificateContainerId
import de.rki.coronawarnapp.covidcertificate.recovery.core.RecoveryCertificate
import de.rki.coronawarnapp.covidcertificate.recovery.core.RecoveryCertificateRepository
import de.rki.coronawarnapp.covidcertificate.recovery.core.RecoveryCertificateWrapper
import de.rki.coronawarnapp.covidcertificate.test.core.TestCertificate
import de.rki.coronawarnapp.covidcertificate.test.core.TestCertificateRepository
import de.rki.coronawarnapp.covidcertificate.test.core.TestCertificateWrapper
import de.rki.coronawarnapp.covidcertificate.vaccination.core.VaccinationCertificate
import de.rki.coronawarnapp.covidcertificate.vaccination.core.repository.VaccinationCertificateRepository
import de.rki.coronawarnapp.covidcertificate.vaccination.core.repository.VaccinationCertificateWrapper
import de.rki.coronawarnapp.util.coroutine.AppScope
import de.rki.coronawarnapp.util.coroutine.DispatcherProvider
import de.rki.coronawarnapp.util.flow.shareLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import javax.inject.Inject

@Reusable
class CertificateProvider @Inject constructor(
    vcRepo: VaccinationCertificateRepository,
    tcRepo: TestCertificateRepository,
    rcRepo: RecoveryCertificateRepository,
    @AppScope private val appScope: CoroutineScope,
    dispatcherProvider: DispatcherProvider
) {

    /**
     * All certificates in the app whether recycled or not
     */
    val allCertificates = combine(
        vcRepo.allCertificates,
        rcRepo.allCertificates,
        tcRepo.allCertificates
    ) { vs, rs, ts ->
        vs.allCertificates + rs.allCertificates + ts.allCertificates
    }.shareLatest(scope = appScope)

    val certificateContainer: Flow<CertificateContainer> = combine(
        rcRepo.certificates,
        tcRepo.certificates,
        vcRepo.certificates
    ) { recoveries, tests, vaccinations ->
        CertificateContainer(recoveries, tests, vaccinations)
    }.shareLatest(scope = appScope + dispatcherProvider.IO)

    /**
     * Finds a [CwaCovidCertificate] by [CertificateContainerId]
     * @throws [Exception] if certificate not found
     */
    suspend fun findCertificate(containerId: CertificateContainerId): CwaCovidCertificate {
        val certificates = certificateContainer.first().allCwaCertificates
        return certificates.find { it.containerId == containerId }!! // Must be a certificate
    }

    data class CertificateContainer(
        val recoveryCertificates: Set<RecoveryCertificateWrapper>,
        val testCertificates: Set<TestCertificateWrapper>,
        val vaccinationCertificates: Set<VaccinationCertificateWrapper>
    ) {

        val recoveryCwaCertificates: Set<RecoveryCertificate> by lazy {
            recoveryCertificates.map { it.recoveryCertificate }.toSet()
        }

        val testCwaCertificates: Set<TestCertificate> by lazy {
            testCertificates.mapNotNull { it.testCertificate }.toSet()
        }

        val vaccinationCwaCertificates: Set<VaccinationCertificate> by lazy {
            vaccinationCertificates.map { it.vaccinationCertificate }.toSet()
        }

        val allCwaCertificates by lazy {
            recoveryCwaCertificates + testCwaCertificates + vaccinationCwaCertificates
        }
    }
}
