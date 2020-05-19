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
import org.apache.kafka.common.metrics.MetricsContext;
import org.junit.Test;

public class TestRestMetricsContext {

    @Test
    public void testMetricsContextNewNameSpace() {
        Map<String, Object> props = new HashMap<>();
        props.put(RestMetricsContext.METRICS_CONTEXT_PREFIX
                + RestMetricsContext.METRICS_RESOURCE_NAME, "root");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = new RestMetricsContext(config);

        System.out.println(context.metadata());

        MetricsContext context2 = context.newNamespace("kafka-consumer");

        System.out.println(context2.metadata());
    }

    @Test
    public void testDefaultMetricsContext() throws Exception {
        TestRestConfig config = new TestRestConfig();
        RestMetricsContext context = new RestMetricsContext(config);

        assertEquals(context.getResourceName(), "rest-utils");
        assertEquals(context.getNameSpace(), "rest-utils");
    }

    @Test
    public void testMetricsContextResourceOverride() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(RestMetricsContext.METRICS_CONTEXT_PREFIX
                + RestMetricsContext.METRICS_RESOURCE_NAME, "root");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = new RestMetricsContext(config);

        assertEquals(context.getResourceName(), "root");
        assertEquals(context.getNameSpace(), "rest-utils");
    }

    @Test
    public void testMetricsContextJMXPrefixPropagation() throws Exception  {
        Map<String, Object> props = new HashMap<>();
        props.put(RestConfig.METRICS_JMX_PREFIX_CONFIG, "FooApp");

        TestRestConfig config = new TestRestConfig(props);
        RestMetricsContext context = new RestMetricsContext(config);

        assertEquals(context.getResourceName(), "FooApp");
        assertEquals(context.getNameSpace(), "FooApp");
    }
}
