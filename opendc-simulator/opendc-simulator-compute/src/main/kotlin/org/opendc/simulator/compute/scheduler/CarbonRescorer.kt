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

import org.opendc.simulator.compute.carbon.CarbonNode
import org.opendc.simulator.compute.carbon.CarbonReceiver
import org.opendc.simulator.compute.infrastructure.SimHost
import org.opendc.simulator.compute.service.ComputeService

/**
 * Weighs the [hosts] of one data center again whenever the carbon intensity of that data center changes.
 *
 * A [FilterScheduler] only weighs a host when it is added or updated, but a
 * [org.opendc.simulator.compute.scheduler.weights.CarbonScoreWeigher] gives a different weight when the carbon intensity
 * changes. The rescorer must be added to the [CarbonNode] after the hosts, so they hold the new intensity when they are
 * weighed again.
 *
 * @param service The service whose scheduler ranks the hosts.
 * @param hosts The hosts of the data center.
 */
public class CarbonRescorer(
    private val service: ComputeService,
    private val hosts: List<SimHost>,
) : CarbonReceiver {
    override fun updateCarbonIntensity(carbonIntensity: Double) {
        for (host in hosts) {
            service.updateHost(host)
        }
    }

    override fun setCarbonNode(carbonNode: CarbonNode?) {}

    override fun removeCarbonNode(carbonNode: CarbonNode?) {}
}
