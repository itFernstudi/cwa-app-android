package de.rki.coronawarnapp.ccl.dccadmission.calculation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.rki.coronawarnapp.ccl.dccadmission.model.DccAdmissionCheckScenarios
import de.rki.coronawarnapp.ccl.dccwalletinfo.calculation.CclJsonFunctions
import io.kotest.matchers.shouldBe
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.joda.time.DateTime
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.TestDispatcherProvider

class DccAdmissionCheckScenariosCalculationTest : BaseTest() {

    @MockK lateinit var dccAdmissionCheckScenarios: DccAdmissionCheckScenarios
    @MockK lateinit var dccAdmissionCheckInput: JsonNode
    @MockK lateinit var dccAdmissionCheckOutput: JsonNode

    @MockK lateinit var cclJsonFunctions: CclJsonFunctions
    @MockK lateinit var mapper: ObjectMapper
    private val dateTime = DateTime.parse("2021-12-30T10:00:00.897+01:00")
    private lateinit var instance: DccAdmissionCheckScenariosCalculation

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)
        coEvery { cclJsonFunctions.evaluateFunction("getDccAdmissionCheckScenarios", dccAdmissionCheckInput) } returns
            dccAdmissionCheckOutput
        every {
            mapper.treeToValue(
                dccAdmissionCheckOutput,
                DccAdmissionCheckScenarios::class.java
            )
        } returns dccAdmissionCheckScenarios
        every { mapper.valueToTree<JsonNode>(any()) } returns dccAdmissionCheckInput

        instance = DccAdmissionCheckScenariosCalculation(
            mapper = mapper,
            cclJsonFunctions = cclJsonFunctions,
            dispatcherProvider = TestDispatcherProvider()
        )
    }

    @Test
    fun `execution works`() = runTest {
        instance.getDccAdmissionCheckScenarios(
            dateTime = dateTime
        ) shouldBe dccAdmissionCheckScenarios
        coVerify {
            cclJsonFunctions.evaluateFunction("getDccAdmissionCheckScenarios", dccAdmissionCheckInput)
        }
    }
}
