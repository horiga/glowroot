/**
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.httpclient;

import java.util.Iterator;

import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ApacheHttpClientPluginIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureHttpGet() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpGet.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello1");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureHttpGetUsingHttpHostArg() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpGetUsingHttpHostArg.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello2");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureHttpPost() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpPost.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello3");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureHttpPostUsingHttpHostArg() throws Exception {
        // when
        Trace trace = container.execute(ExecuteHttpPostUsingHttpHostArg.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello4");

        assertThat(i.hasNext()).isFalse();
    }

    private static HttpClient createHttpClient() throws Exception {
        try {
            return (HttpClient) Class.forName("org.apache.http.impl.client.HttpClients")
                    .getMethod("createDefault").invoke(null);
        } catch (ClassNotFoundException e) {
            // httpclient prior to 4.3.0
            return (HttpClient) Class.forName("org.apache.http.impl.client.DefaultHttpClient")
                    .newInstance();
        }
    }

    public static class ExecuteHttpGet extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            HttpClient httpClient = createHttpClient();
            HttpGet httpGet = new HttpGet("http://localhost:" + getPort() + "/hello1");
            httpClient.execute(httpGet);
        }
    }

    public static class ExecuteHttpGetUsingHttpHostArg extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            HttpClient httpClient = createHttpClient();
            HttpHost httpHost = new HttpHost("localhost", getPort());
            HttpGet httpGet = new HttpGet("/hello2");
            httpClient.execute(httpHost, httpGet);
        }
    }

    public static class ExecuteHttpPost extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            HttpClient httpClient = createHttpClient();
            HttpPost httpPost = new HttpPost("http://localhost:" + getPort() + "/hello3");
            httpClient.execute(httpPost);
        }
    }

    public static class ExecuteHttpPostUsingHttpHostArg extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            HttpClient httpClient = createHttpClient();
            HttpHost httpHost = new HttpHost("localhost", getPort());
            HttpPost httpPost = new HttpPost("/hello4");
            httpClient.execute(httpHost, httpPost);
        }
    }
}
