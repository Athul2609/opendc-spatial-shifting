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

package org.opendc.simulator.compute.scheduler.weights

import org.opendc.simulator.compute.infrastructure.SimHost

/**
 * A [HostWeigher] that favours hosts in the data centers with the cleanest energy, scoring each host by
 * `nowWeight * carbonNow + forecastWeight * carbonForecast`.
 *
 * The [org.opendc.simulator.compute.scheduler.FilterScheduler] ranks the highest weight first, so the weight is the
 * negated score: with a positive [multiplier], the host with the lowest carbon score is ranked first.
 *
 * The weight depends on the time as well as the host, so the hosts must be weighed again whenever the carbon intensity
 * of their data center changes; see [org.opendc.simulator.compute.scheduler.CarbonRescorer].
 *
 * @param multiplier The weight of this weigher relative to the other weighers.
 * @param nowWeight The weight of the current carbon intensity in the score.
 * @param forecastWeight The weight of the forecast carbon intensity in the score.
 * @param forecastHorizon How far ahead, in ms, the forecast looks. The forecast is the intensity the trace gives at that
 *   time (a placeholder for a better forecast, such as the mean over the horizon).
 */
public class CarbonScoreWeigher(
    override val multiplier: Double = 1.0,
    private val nowWeight: Double = 1.0,
    private val forecastWeight: Double = 1.0,
    private val forecastHorizon: Long = DEFAULT_FORECAST_HORIZON,
) : HostWeigher {
    init {
        require(forecastHorizon >= 0) { "The forecast horizon must not be negative, but is $forecastHorizon" }
    }

    override fun getWeight(host: SimHost): Double {
        val score = nowWeight * host.carbonIntensity + forecastWeight * host.forecastCarbonIntensity(forecastHorizon)
        return -score
    }

    override fun toString(): String = "CarbonScoreWeigher"

    public companion object {
        /** The default forecast horizon: 10 hours, in ms. */
        public const val DEFAULT_FORECAST_HORIZON: Long = 10 * 60 * 60 * 1000L
    }
}
