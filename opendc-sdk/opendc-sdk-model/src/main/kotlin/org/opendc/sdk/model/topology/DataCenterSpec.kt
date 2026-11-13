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

package org.opendc.sdk.model.topology

import kotlinx.serialization.Serializable
import org.opendc.sdk.model.validation.Validatable
import org.opendc.sdk.model.validation.ValidationIssue
import org.opendc.sdk.model.validation.prefixed
import org.opendc.sdk.model.validation.validateEach

/**
 * A data center: clusters of hosts that share a power source, a carbon trace, and an SLA profile.
 *
 * @property region The region the data center is in, or null if it has none. Checked against the allowed regions of
 *   an SLA cap filter.
 * @property price The price of running in the data center. Checked against the maximum price of an SLA cap filter.
 * @property availability The availability of the hosts in the data center, between 0 and 1. A static value for now.
 *   Checked against the minimum availability of an SLA cap filter.
 */
@Serializable
public data class DataCenterSpec(
    public val clusters: List<ClusterSpec>,
    public val name: String = "DC",
    public val powerSource: PowerSourceSpec = PowerSourceSpec(),
    public val battery: BatterySpec? = null,
    public val region: String? = null,
    public val price: Double = 0.0,
    public val availability: Double = 1.0,
) : Validatable {
    override fun validate(): List<ValidationIssue> =
        buildList {
            if (clusters.isEmpty()) add(ValidationIssue("clusters", "must not be empty"))
            addAll(clusters.validateEach("clusters"))
            addAll(powerSource.validate().prefixed("powerSource"))
            if (!price.isFinite() || price < 0.0) add(ValidationIssue("price", "must be finite and >= 0"))
            if (availability !in 0.0..1.0) add(ValidationIssue("availability", "must be between 0 and 1"))
        }
}
