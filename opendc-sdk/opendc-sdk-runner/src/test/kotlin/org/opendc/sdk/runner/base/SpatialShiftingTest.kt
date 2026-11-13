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

package org.opendc.sdk.runner.base

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.opendc.common.units.DataSize
import org.opendc.common.units.Frequency
import org.opendc.common.units.Power
import org.opendc.common.units.TimeDelta
import org.opendc.common.units.Timestamp
import org.opendc.sdk.model.carbon.CarbonIntensitySpec
import org.opendc.sdk.model.carbon.InlineTraceBasedCarbonSpec
import org.opendc.sdk.model.scheduler.AllocationPolicySpec
import org.opendc.sdk.model.scheduler.CarbonScoreWeigherSpec
import org.opendc.sdk.model.scheduler.ComputeHostFilterSpec
import org.opendc.sdk.model.scheduler.FilterAllocationPolicySpec
import org.opendc.sdk.model.scheduler.HostFilterSpec
import org.opendc.sdk.model.scheduler.RamFilterSpec
import org.opendc.sdk.model.scheduler.SlaCapFilterSpec
import org.opendc.sdk.model.scheduler.VCpuFilterSpec
import org.opendc.sdk.model.topology.ClusterSpec
import org.opendc.sdk.model.topology.CpuSpec
import org.opendc.sdk.model.topology.DataCenterSpec
import org.opendc.sdk.model.topology.HostSpec
import org.opendc.sdk.model.topology.LinearPowerModelSpec
import org.opendc.sdk.model.topology.MemorySpec
import org.opendc.sdk.model.topology.PowerSourceSpec
import org.opendc.sdk.model.topology.TopologySpec
import org.opendc.sdk.runner.base.harness.MINUTE
import org.opendc.sdk.runner.base.harness.TestMetricExporter
import org.opendc.sdk.runner.base.harness.createTestTask
import org.opendc.sdk.runner.base.harness.fragment
import org.opendc.sdk.runner.base.harness.runTest
import java.time.Instant

/**
 * End-to-end tests of spatial shifting: tasks are placed on the host in the data center with the cleanest energy that
 * meets the SLA caps of the scenario.
 *
 * Every data center has one host, named after it, with four 2 GHz cores. Each task uses one core at full speed.
 */
class SpatialShiftingTest {
    @Test
    fun `a task is placed in the data center with the cleanest energy`() {
        val monitor =
            runTest(
                TopologySpec(listOf(dataCenter(DIRTY, carbonTrace(0L to 300.0)), dataCenter(CLEAN, carbonTrace(0L to 50.0)))),
                listOf(task(0, submittedAt = 0)),
                allocationPolicy = carbonPolicy(),
            )

        // The hosts share a model, and the dirty host has the lowest id, so it stands in for both unless the empty
        // hosts are grouped per data center
        assertRanOn(monitor, CLEAN, notOn = DIRTY)
    }

    @Test
    fun `the forecast can outweigh the current carbon intensity`() {
        // NOW is cleaner now, but its trace turns dirty within the forecast horizon
        val monitor =
            runTest(
                TopologySpec(
                    listOf(
                        dataCenter(NOW, carbonTrace(0L to 50.0, 60 * MINUTE to 500.0)),
                        dataCenter(LATER, carbonTrace(0L to 100.0)),
                    ),
                ),
                listOf(task(0, submittedAt = 0)),
                allocationPolicy = carbonPolicy(),
            )

        assertRanOn(monitor, LATER, notOn = NOW)
    }

    @Test
    fun `hosts are weighed again when the carbon intensity of their data center changes`() {
        // A is the cleanest until it turns dirty at 10 min. Task 0 keeps A busy throughout, so A is only weighed again
        // on the carbon change: without it, A would keep its clean score and get task 1 too.
        val monitor =
            runTest(
                TopologySpec(
                    listOf(
                        dataCenter(A, carbonTrace(0L to 50.0, 10 * MINUTE to 500.0)),
                        dataCenter(B, carbonTrace(0L to 300.0)),
                    ),
                ),
                listOf(task(0, submittedAt = 0, duration = 60 * MINUTE), task(1, submittedAt = 20 * MINUTE, duration = 10 * MINUTE)),
                allocationPolicy = carbonPolicy(forecastHorizon = TimeDelta.zero),
            )

        assertAll(
            { assertTrue(monitor.hostCpuSupplied.getValue(A).sum() > 0.0) { "Task 0 did not run on $A" } },
            { assertTrue(monitor.hostCpuSupplied.getValue(B).sum() > 0.0) { "Task 1 did not run on $B" } },
            // A never runs both tasks at once
            { assertTrue(monitor.hostCpuSupplied.getValue(A).all { it <= CORE_SPEED }) { "Both tasks ran on $A" } },
        )
    }

    @Test
    fun `a task is placed in the cleanest data center in an allowed region`() {
        val monitor =
            runTest(
                TopologySpec(
                    listOf(
                        dataCenter(DIRTY, carbonTrace(0L to 300.0), region = "eu"),
                        dataCenter(CLEAN, carbonTrace(0L to 50.0), region = "us"),
                    ),
                ),
                listOf(task(0, submittedAt = 0)),
                allocationPolicy = carbonPolicy(SlaCapFilterSpec(allowedRegions = setOf("eu"))),
            )

        assertRanOn(monitor, DIRTY, notOn = CLEAN)
    }

    @Test
    fun `a task is placed in the cleanest data center within the price and availability caps`() {
        val monitor =
            runTest(
                TopologySpec(
                    listOf(
                        dataCenter(DIRTY, carbonTrace(0L to 300.0), price = 0.1, availability = 0.999),
                        dataCenter(CLEAN, carbonTrace(0L to 50.0), price = 0.5, availability = 0.999),
                        dataCenter(UNRELIABLE, carbonTrace(0L to 10.0), price = 0.1, availability = 0.9),
                    ),
                ),
                listOf(task(0, submittedAt = 0)),
                allocationPolicy = carbonPolicy(SlaCapFilterSpec(maxPrice = 0.2, minAvailability = 0.99)),
            )

        assertAll(
            { assertRanOn(monitor, DIRTY, notOn = CLEAN) },
            { assertEquals(0.0, monitor.hostCpuSupplied.getValue(UNRELIABLE).sum()) { "The task ran on $UNRELIABLE" } },
        )
    }

    private fun assertRanOn(
        monitor: TestMetricExporter,
        host: String,
        notOn: String,
    ) {
        assertAll(
            { assertTrue(monitor.hostCpuSupplied.getValue(host).sum() > 0.0) { "The task did not run on $host" } },
            { assertEquals(0.0, monitor.hostCpuSupplied.getValue(notOn).sum()) { "The task ran on $notOn" } },
        )
    }

    private fun carbonPolicy(
        vararg extraFilters: HostFilterSpec,
        forecastHorizon: TimeDelta = TimeDelta.ofHours(10),
    ): AllocationPolicySpec =
        FilterAllocationPolicySpec(
            filters = listOf(ComputeHostFilterSpec, VCpuFilterSpec(1.0), RamFilterSpec(1.0)) + extraFilters,
            weighers = listOf(CarbonScoreWeigherSpec(forecastHorizon = forecastHorizon)),
        )

    /** A data center named [name] with a single host, also named [name], whose energy follows [carbon]. */
    private fun dataCenter(
        name: String,
        carbon: InlineTraceBasedCarbonSpec,
        region: String? = null,
        price: Double = 0.0,
        availability: Double = 1.0,
    ): DataCenterSpec =
        DataCenterSpec(
            clusters =
                listOf(
                    ClusterSpec(
                        name = "C-$name",
                        hosts =
                            listOf(
                                HostSpec(
                                    name = name,
                                    cpu = CpuSpec(coreCount = 4, coreSpeed = Frequency.ofMHz(CORE_SPEED)),
                                    memory = MemorySpec(size = DataSize.ofGB(128)),
                                    cpuPowerModel = LinearPowerModelSpec(idlePower = Power.ofWatts(100), maxPower = Power.ofWatts(200)),
                                ),
                            ),
                    ),
                ),
            name = name,
            powerSource = PowerSourceSpec(carbon = carbon),
            region = region,
            price = price,
            availability = availability,
        )

    /** A task using one core at full speed, submitted [submittedAt] ms after the start, running for [duration] ms. */
    private fun task(
        id: Int,
        submittedAt: Long,
        duration: Long = 10 * MINUTE,
    ) = createTestTask(
        id = id,
        submissionTime = Instant.ofEpochMilli(submittedAt).toString().removeSuffix("Z"),
        fragments = listOf(fragment(duration, CORE_SPEED)),
    )

    /**
     * An inline carbon trace in which the carbon intensity changes at the given times, in ms after the start of the
     * simulation, which starts at the Unix epoch.
     */
    private fun carbonTrace(vararg intensities: Pair<Long, Double>): InlineTraceBasedCarbonSpec =
        InlineTraceBasedCarbonSpec(intensities.map { (time, intensity) -> CarbonIntensitySpec(Timestamp.ofEpochMs(time), intensity) })

    private companion object {
        const val CORE_SPEED = 2000.0

        const val DIRTY = "DIRTY"
        const val CLEAN = "CLEAN"
        const val UNRELIABLE = "UNRELIABLE"
        const val NOW = "NOW"
        const val LATER = "LATER"
        const val A = "A"
        const val B = "B"
    }
}
