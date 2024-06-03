/**
 * Copyright 2014 Confluent Inc.
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

import static io.confluent.rest.metrics.TestRestMetricsContext.RESOURCE_LABEL_TYPE;
import static org.apache.kafka.clients.CommonClientConfigs.METRICS_CONTEXT_PREFIX;
import static org.apache.kafka.common.metrics.MetricsContext.NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.confluent.rest.RestConfig;
import io.confluent.rest.TestRestConfig;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class RestMetricsContextTest {

    @Test
    public void testDefaultMetricsContext() throws Exception {
        TestRestConfig config = new TestRestConfig();
        RestMetricsContext context = config.getMetricsContext();

        assertEquals(context.getLabel(RESOURCE_LABEL_TYPE), "rest-utils");
        assertEquals(context.getLabel(NAMESPACE), "rest-utils");
    }

    @Test
    public void testMetricsContextResourceOverride() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(METRICS_CONTEXT_PREFIX
                + RESOURCE_LABEL_TYPE, "root");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = config.getMetricsContext();

        assertEquals(context.getLabel(RESOURCE_LABEL_TYPE), "root");
        assertEquals(context.getLabel(NAMESPACE), "rest-utils");
    }

    @Test
    public void testMetricsContextJMXPrefixPropagation() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(RestConfig.METRICS_JMX_PREFIX_CONFIG, "FooApp");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = config.getMetricsContext();

        assertEquals(context.getLabel(NAMESPACE), "FooApp");
    }

    @Test
    public void testMetricsContextPutNamespaceLabelStripResourcePrefix() throws Exception  {
        Map<String, Object> props = new HashMap<>();

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = config.getMetricsContext();

        assertEquals("rest-utils", context.getLabel(NAMESPACE));
    }

    @Test
    public void testMetricsContextResourceLabelNew() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(METRICS_CONTEXT_PREFIX
                + RESOURCE_LABEL_TYPE, "root");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = config.getMetricsContext();

        String RESOURCE_CLUSTER_ID =
                TestRestMetricsContext.RESOURCE_LABEL_PREFIX + "cluster.id";

        context.setLabel(
                RESOURCE_CLUSTER_ID,
                "rest-utils-bootstrap");

        assertEquals(context.getLabel(RESOURCE_LABEL_TYPE), "root");
        assertEquals(context.getLabel(NAMESPACE), "rest-utils");
        assertEquals(context.getLabel(RESOURCE_CLUSTER_ID), "rest-utils-bootstrap");
    }

    @Test
    public void testMetricsContextResourceNonStringValue() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(METRICS_CONTEXT_PREFIX
                + RESOURCE_LABEL_TYPE, "root");
        props.put(METRICS_CONTEXT_PREFIX + "notString",
                this.getClass());

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = config.getMetricsContext();

        assertEquals(context.getLabel(RESOURCE_LABEL_TYPE), "root");
        assertEquals(context.getLabel(NAMESPACE), "rest-utils");
        assertEquals(context.getLabel("notString"), this.getClass().toString());
    }
}
