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

package org.opendc.simulator.compute.scheduler.filters

import org.opendc.simulator.compute.infrastructure.SimHost
import org.opendc.simulator.compute.task.SimTask

/**
 * A [HostFilter] that keeps only the hosts that meet the SLA caps of the scenario: a host must be in one of the
 * [allowedRegions], cost at most [maxPrice], and have an availability of at least [minAvailability].
 *
 * The caps hold for every task of the scenario. In the future they should come from the SLA of each task instead.
 *
 * @param allowedRegions The regions a host may be in, or null to allow every region. A host without a region is only
 *   allowed when every region is.
 * @param maxPrice The highest price a host may have.
 * @param minAvailability The lowest availability a host may have, between 0 and 1.
 */
public class SlaCapFilter(
    private val allowedRegions: Set<String>? = null,
    private val maxPrice: Double = Double.POSITIVE_INFINITY,
    private val minAvailability: Double = 0.0,
) : HostFilter {
    override fun test(
        host: SimHost,
        task: SimTask,
    ): Boolean =
        (allowedRegions == null || host.region in allowedRegions) &&
            host.price <= maxPrice &&
            host.availability >= minAvailability

    override fun toString(): String = "SlaCapFilter"
}
