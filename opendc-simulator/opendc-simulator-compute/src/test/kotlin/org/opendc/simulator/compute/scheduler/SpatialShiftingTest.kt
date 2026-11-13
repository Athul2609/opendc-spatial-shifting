/*
 * Copyright (c) 2026 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.simulator.compute.scheduler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opendc.common.units.DataSize
import org.opendc.common.units.Frequency
import org.opendc.common.units.TimeDelta
import org.opendc.common.units.Timestamp
import org.opendc.simulator.compute.carbon.CarbonFragment
import org.opendc.simulator.compute.carbon.CarbonNode
import org.opendc.simulator.compute.infrastructure.SimHost
import org.opendc.simulator.compute.models.CpuModel
import org.opendc.simulator.compute.models.MachineModel
import org.opendc.simulator.compute.models.MemoryUnit
import org.opendc.simulator.compute.power.PowerModel
import org.opendc.simulator.compute.scheduler.filters.ComputeFilter
import org.opendc.simulator.compute.scheduler.filters.SlaCapFilter
import org.opendc.simulator.compute.scheduler.filters.VCpuFilter
import org.opendc.simulator.compute.scheduler.weights.CarbonScoreWeigher
import org.opendc.simulator.compute.scheduler.weights.RamWeigher
import org.opendc.simulator.compute.service.ComputeService
import org.opendc.simulator.compute.task.SimTask
import org.opendc.simulator.flow.engine.FlowEngine
import org.opendc.simulator.flow.graph.FlowDistributor
import org.opendc.simulator.flow.graph.distributionPolicies.FlowDistributorFactory
import java.time.Instant
import java.time.InstantSource

/**
 * Test suite for spatial shifting: the [CarbonScoreWeigher], the [SlaCapFilter], the [CarbonRescorer], and the carbon
 * forecast of the [CarbonNode].
 */
internal class SpatialShiftingTest {
    @Test
    fun testForecastJumpsAheadInTheTrace() {
        val node = carbonNode(nowMs = 0)

        assertEquals(100.0, node.getCarbonIntensityAhead(0))
        assertEquals(100.0, node.getCarbonIntensityAhead(HOUR - 1))
        assertEquals(50.0, node.getCarbonIntensityAhead(HOUR))
        assertEquals(300.0, node.getCarbonIntensityAhead(2 * HOUR + 1))
    }

    @Test
    fun testForecastPastTheEndOfTheTraceGivesTheLastIntensity() {
        assertEquals(300.0, carbonNode(nowMs = 0).getCarbonIntensityAhead(100 * HOUR))
    }

    @Test
    fun testForecastLooksAheadFromNow() {
        // Half an hour in, an hour ahead is in the second fragment
        assertEquals(50.0, carbonNode(nowMs = HOUR / 2).getCarbonIntensityAhead(HOUR))
    }

    @Test
    fun testForecastRejectsNegativeLookahead() {
        assertThrows<IllegalArgumentException> { carbonNode(nowMs = 0).getCarbonIntensityAhead(-1) }
    }

    @Test
    fun testWeightIsTheNegatedCarbonScore() {
        val host = host(0, carbonNow = 100.0, carbonForecast = 40.0)

        assertEquals(-140.0, CarbonScoreWeigher().getWeight(host))
        assertEquals(-(2 * 100.0 + 0.5 * 40.0), CarbonScoreWeigher(nowWeight = 2.0, forecastWeight = 0.5).getWeight(host))
    }

    @Test
    fun testHostWithoutCarbonTraceForecastsItsCurrentIntensity() {
        val host = realHost(0)
        host.updateCarbonIntensity(70.0)

        assertEquals(-140.0, CarbonScoreWeigher().getWeight(host))
    }

    @Test
    fun testPlacesOnTheCleanestHost() {
        val scheduler = FilterScheduler(listOf(ComputeFilter(), VCpuFilter(1.0)), listOf(CarbonScoreWeigher()))
        val dirty = host(0, carbonNow = 300.0, carbonForecast = 300.0, modelId = 0)
        val clean = host(1, carbonNow = 50.0, carbonForecast = 100.0, modelId = 1)
        val middle = host(2, carbonNow = 100.0, carbonForecast = 100.0, modelId = 2)
        listOf(dirty, clean, middle).forEach(scheduler::addHost)

        assertEquals(clean, scheduler.select(request()).host)
    }

    @Test
    fun testForecastCanOutweighTheCurrentIntensity() {
        val scheduler = FilterScheduler(listOf(ComputeFilter()), listOf(CarbonScoreWeigher()))
        // Cleaner now, but much dirtier in the forecast
        val cleanNow = host(0, carbonNow = 50.0, carbonForecast = 400.0, modelId = 0)
        val cleanLater = host(1, carbonNow = 100.0, carbonForecast = 100.0, modelId = 1)
        listOf(cleanNow, cleanLater).forEach(scheduler::addHost)

        assertEquals(cleanLater, scheduler.select(request()).host)
    }

    @Test
    fun testRanksAgainWhenTheCarbonIntensityChanges() {
        val scheduler = FilterScheduler(listOf(ComputeFilter()), listOf(CarbonScoreWeigher()))
        val a = host(0, carbonNow = 50.0, carbonForecast = 50.0, modelId = 0)
        val b = host(1, carbonNow = 100.0, carbonForecast = 100.0, modelId = 1)
        listOf(a, b).forEach(scheduler::addHost)
        assertEquals(a, scheduler.select(request()).host)

        // The data center of a becomes dirty; until it is weighed again, the ranking is stale
        a.updateCarbonIntensity(500.0)
        a.setCarbonNode(forecast(500.0))
        assertEquals(a, scheduler.select(request()).host)

        scheduler.updateHost(a)
        assertEquals(b, scheduler.select(request()).host)
    }

    @Test
    fun testRescorerUpdatesEveryHostOfItsDataCenter() {
        val service = mockk<ComputeService>(relaxed = true)
        val hosts = listOf(realHost(0), realHost(1))

        CarbonRescorer(service, hosts).updateCarbonIntensity(42.0)

        verify(exactly = 1) { service.updateHost(hosts[0]) }
        verify(exactly = 1) { service.updateHost(hosts[1]) }
    }

    @Test
    fun testCarbonWeigherMakesTheSchedulerCarbonAware() {
        assertTrue(FilterScheduler(emptyList(), listOf(RamWeigher(), CarbonScoreWeigher())).weighsCarbon)
        assertFalse(FilterScheduler(emptyList(), listOf(RamWeigher())).weighsCarbon)
    }

    @Test
    fun testSlaCapFilterChecksRegion() {
        val filter = SlaCapFilter(allowedRegions = setOf("eu-west", "eu-north"))
        val task = task()

        assertTrue(filter.test(realHost(0, region = "eu-west"), task))
        assertFalse(filter.test(realHost(1, region = "us-east"), task))
        assertFalse(filter.test(realHost(2, region = null), task))
        assertTrue(SlaCapFilter().test(realHost(3, region = null), task))
    }

    @Test
    fun testSlaCapFilterChecksPrice() {
        val filter = SlaCapFilter(maxPrice = 0.2)
        val task = task()

        assertTrue(filter.test(realHost(0, price = 0.2), task))
        assertFalse(filter.test(realHost(1, price = 0.21), task))
    }

    @Test
    fun testSlaCapFilterChecksAvailability() {
        val filter = SlaCapFilter(minAvailability = 0.99)
        val task = task()

        assertTrue(filter.test(realHost(0, availability = 0.999), task))
        assertFalse(filter.test(realHost(1, availability = 0.95), task))
    }

    @Test
    fun testPlacesOnTheCleanestHostThatMeetsTheSla() {
        val scheduler =
            FilterScheduler(
                listOf(ComputeFilter(), SlaCapFilter(allowedRegions = setOf("eu"), maxPrice = 0.3)),
                listOf(CarbonScoreWeigher()),
            )
        val cleanestWrongRegion = host(0, carbonNow = 10.0, carbonForecast = 10.0, modelId = 0, region = "us")
        val cleanTooExpensive = host(1, carbonNow = 20.0, carbonForecast = 20.0, modelId = 1, region = "eu", price = 0.5)
        val allowed = host(2, carbonNow = 200.0, carbonForecast = 200.0, modelId = 2, region = "eu", price = 0.1)
        listOf(cleanestWrongRegion, cleanTooExpensive, allowed).forEach(scheduler::addHost)

        assertEquals(allowed, scheduler.select(request()).host)
    }

    /** A carbon node, at [nowMs] into the simulation, with 100 for the first hour, 50 for the second, and 300 after. */
    private fun carbonNode(nowMs: Long): CarbonNode {
        val clock = mockk<InstantSource>()
        every { clock.millis() } returns nowMs
        val engine = mockk<FlowEngine>(relaxed = true)
        every { engine.clock } returns clock

        val fragments =
            listOf(
                CarbonFragment(0, HOUR, 100.0),
                CarbonFragment(HOUR, 2 * HOUR, 50.0),
                CarbonFragment(2 * HOUR, 3 * HOUR, 300.0),
            )
        return CarbonNode(engine, fragments, 0)
    }

    /** A carbon node that forecasts [intensity] at every horizon. */
    private fun forecast(intensity: Double): CarbonNode {
        val node = mockk<CarbonNode>()
        every { node.getCarbonIntensityAhead(any()) } returns intensity
        return node
    }

    /** A host whose data center has a carbon intensity of [carbonNow] now, and of [carbonForecast] in the forecast. */
    private fun host(
        id: Int,
        carbonNow: Double,
        carbonForecast: Double,
        modelId: Int = 0,
        region: String? = null,
        price: Double = 0.0,
    ): SimHost {
        val host = realHost(id, modelId, region, price)
        host.setCarbonNode(forecast(carbonForecast))
        host.updateCarbonIntensity(carbonNow)
        return host
    }

    /** A real host with a mocked engine, with 8 cores and 16 GiB. */
    private fun realHost(
        id: Int,
        modelId: Int = 0,
        region: String? = null,
        price: Double = 0.0,
        availability: Double = 1.0,
    ): SimHost {
        val machine =
            MachineModel(
                CpuModel(0, 8, 2600.0, "vendor", "model", "arch"),
                MemoryUnit("vendor", "model", 3200.0, 16_384),
                null,
                FlowDistributorFactory.DistributionPolicy.MAX_MIN_FAIRNESS,
                FlowDistributorFactory.DistributionPolicy.MAX_MIN_FAIRNESS,
            )
        val host =
            SimHost(
                id,
                "H$id",
                0,
                InstantSource.fixed(Instant.EPOCH),
                mockk<FlowEngine>(relaxed = true),
                machine,
                mockk<PowerModel>(relaxed = true),
                null,
                0.0,
                1.0,
                mockk<FlowDistributor>(relaxed = true),
                region,
                price,
                availability,
            )
        host.modelId = modelId
        return host
    }

    private fun task(): SimTask =
        SimTask(
            0,
            Timestamp.zero,
            TimeDelta.ofMs(1000),
            1,
            Frequency.ofMHz(2600.0),
            DataSize.ofMiB(1024),
            0,
            Frequency.zero,
            DataSize.zero,
            null,
            false,
            Timestamp.ofEpochMs(Long.MAX_VALUE),
            null,
            null,
        )

    private fun request(): MutableIterator<SchedulingRequest> = mutableListOf(SchedulingRequest(task(), Timestamp.zero)).iterator()

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
