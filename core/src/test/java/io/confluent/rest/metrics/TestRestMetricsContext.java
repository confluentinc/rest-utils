/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.rest.metrics;

import io.confluent.rest.TestRestConfig;
import org.apache.kafka.common.metrics.MetricsContext;

public class TestRestMetricsContext extends RestMetricsContext {
    /**
     * MetricsContext Label's for use by Confluent's TelemetryReporter
     */
    public static final String RESOURCE_LABEL_PREFIX = "resource.";
    public static final String RESOURCE_LABEL_TYPE = RESOURCE_LABEL_PREFIX + "type";
    public static final String RESOURCE_LABEL_COMMIT_ID = RESOURCE_LABEL_PREFIX + "commit.id";

    /**
     * {@link io.confluent.rest.Application} {@link MetricsContext} configuration.
     */
    public TestRestMetricsContext(TestRestConfig config) {
        /* Copy all configuration properties prefixed into metadata instance. */
        super(config);

        /* Never overwrite preexisting resource labels */
        this.setResourceLabel(RESOURCE_LABEL_TYPE,
                config.getString(TestRestConfig.METRICS_JMX_PREFIX_CONFIG));
    }

    /**
     * Sets a {@link MetricsContext} key, value pair.
     */
    @Override
    protected void setLabel(String labelKey, String labelValue) {
        /* Remove resource label if present */
        if (labelKey.startsWith(RESOURCE_LABEL_PREFIX))
                setResourceLabel(labelKey, labelValue);

        super.setLabel(labelKey, labelValue);
    }

    /**
     * Sets {@link MetricsContext} resource label if not previously set.
     */
    protected void setResourceLabel(String resource, String value) {
        contextLabels.putIfAbsent(resource, value);
    }

}
