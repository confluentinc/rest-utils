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

import static org.junit.Assert.assertEquals;

import io.confluent.rest.RestConfig;
import io.confluent.rest.TestRestConfig;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class TestRestMetricsContext {

    @Test
    public void testDefaultMetricsContext() throws Exception {
        TestRestConfig config = new TestRestConfig();
        RestMetricsContext context = new RestMetricsContext(config);

        assertEquals(context.getResourceType(), "rest-utils");
        assertEquals(context.getNamespace(), "rest-utils");
    }

    @Test
    public void testMetricsContextResourceOverride() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(RestMetricsContext.METRICS_CONTEXT_PREFIX
                + RestMetricsContext.RESOURCE_LABEL_TYPE, "root");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = new RestMetricsContext(config);

        assertEquals(context.getResourceType(), "root");
        assertEquals(context.getNamespace(), "rest-utils");
    }

    @Test
    public void testMetricsContextJMXPrefixPropagation() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(RestConfig.METRICS_JMX_PREFIX_CONFIG, "FooApp");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = new RestMetricsContext(config);

        assertEquals(context.getResourceType(), "FooApp");
        assertEquals(context.getNamespace(), "FooApp");
    }

    @Test
    public void testMetricsContextPutNamespaceLabelStripResourcePrefix() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(RestMetricsContext.METRICS_CONTEXT_PREFIX
                + RestMetricsContext.RESOURCE_LABEL_TYPE, "root");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = new RestMetricsContext(config);

        context.putLabel(RestMetricsContext.RESOURCE_LABEL_TYPE,
                "rest-utils-resource");

        assertEquals(context.getResourceType(), "root");
        assertEquals(context.getNamespace(), "rest-utils");
    }

    @Test
    public void testMetricsContextResourceLabelSetIfAbsent() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(RestMetricsContext.METRICS_CONTEXT_PREFIX
                + RestMetricsContext.RESOURCE_LABEL_TYPE, "root");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = new RestMetricsContext(config);

        context.putResourceLabel(RestMetricsContext.RESOURCE_LABEL_TYPE,
                "rest-utils-resource");

        assertEquals(context.getResourceType(), "root");
        assertEquals(context.getNamespace(), "rest-utils");
    }

    @Test
    public void testMetricsContextResourceLabelNew() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(RestMetricsContext.METRICS_CONTEXT_PREFIX
                + RestMetricsContext.RESOURCE_LABEL_TYPE, "root");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = new RestMetricsContext(config);

        String RESOURCE_CLUSTER_ID =
                RestMetricsContext.RESOURCE_LABEL_PREFIX + "cluster.id";

        context.putResourceLabel(
                RESOURCE_CLUSTER_ID,
                "rest-utils-bootstrap");

        assertEquals(context.getResourceType(), "root");
        assertEquals(context.getNamespace(), "rest-utils");
        assertEquals(context.metadata().get(RESOURCE_CLUSTER_ID), "rest-utils-bootstrap");
    }

    @Test
    public void testMetricsContextResourceNonStringValue() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(RestMetricsContext.METRICS_CONTEXT_PREFIX
                + RestMetricsContext.RESOURCE_LABEL_TYPE, "root");
        props.put(RestMetricsContext.METRICS_CONTEXT_PREFIX + "notString",
                this.getClass());

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = new RestMetricsContext(config);


        assertEquals(context.getResourceType(), "root");
        assertEquals(context.getNamespace(), "rest-utils");
        assertEquals(context.metadata().get("notString"), this.getClass().toString());
    }
}
