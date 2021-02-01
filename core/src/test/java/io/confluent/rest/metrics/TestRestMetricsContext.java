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

import java.util.Map;
import org.apache.kafka.common.metrics.MetricsContext;


public final class TestRestMetricsContext {
    /**
     * MetricsContext Label's for use by Confluent's TelemetryReporter
     */
    private final RestMetricsContext metricsContext;
    public static final String RESOURCE_LABEL_PREFIX = "resource.";
    public static final String RESOURCE_LABEL_TYPE = RESOURCE_LABEL_PREFIX + "type";

    public TestRestMetricsContext(String namespace, Map<String, Object> config) {
        metricsContext = new RestMetricsContext(namespace, config);

        this.setResourceLabel(RESOURCE_LABEL_TYPE,
                namespace);
    }

    /**
     * Sets a {@link MetricsContext} key, value pair.
     */
    public void setLabel(String labelKey, String labelValue) {
        /* Remove resource label if present */
        if (labelKey.startsWith(RESOURCE_LABEL_PREFIX))
                setResourceLabel(labelKey, labelValue);

        metricsContext.setLabel(labelKey, labelValue);
    }

    /**
     * Sets {@link MetricsContext} resource label if not previously set.
     */
    private void setResourceLabel(String resource, String value) {
        if (metricsContext.getLabel(resource) == null) {
            metricsContext.setLabel(resource, value);
        }
    }

    public RestMetricsContext metricsContext() {
        return metricsContext;
    }

}
