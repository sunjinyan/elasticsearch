/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.backwards;

import org.apache.http.HttpHost;
import org.elasticsearch.Version;
import org.elasticsearch.backwards.IndexingIT.Node;
import org.elasticsearch.backwards.IndexingIT.Nodes;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.CheckedRunnable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.test.rest.yaml.ObjectPath;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class SearchWithMinCompatibleSearchNodeIT extends ESRestTestCase {

    private static String index = "test_min_version";
    private static int numShards;
    private static int numReplicas = 1;
    private static int numDocs;
    private static Nodes nodes;
    private static List<Node> allNodes;
    private static Version bwcVersion;
    private static Version newVersion;

    @Before
    public void prepareTestData() throws IOException {
        nodes = IndexingIT.buildNodeAndVersions(client());
        numShards = nodes.size();
        numDocs = randomIntBetween(numShards, 16);
        allNodes = new ArrayList<>();
        allNodes.addAll(nodes.getBWCNodes());
        allNodes.addAll(nodes.getNewNodes());
        bwcVersion = nodes.getBWCNodes().get(0).getVersion();
        newVersion = nodes.getNewNodes().get(0).getVersion();

        if (client().performRequest(new Request("HEAD", "/" + index)).getStatusLine().getStatusCode() == 404) {
            createIndex(index, Settings.builder()
                .put(IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), numShards)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numReplicas).build());
            for (int i = 0; i < numDocs; i++) {
                Request request = new Request("PUT", index + "/_doc/" + i);
                request.setJsonEntity("{\"test\": \"test_" + randomAlphaOfLength(2) + "\"}");
                assertOK(client().performRequest(request));
            }
            ensureGreen(index);
        }
    }

    public void testMinVersionAsNewVersion() throws Exception {
        try (RestClient client = buildClient(restClientSettings(),
            allNodes.stream().map(Node::getPublishAddress).toArray(HttpHost[]::new))) {
            Request newVersionRequest = new Request("POST",
                index + "/_search?min_compatible_shard_node=" + newVersion + "&ccs_minimize_roundtrips=false");
            assertBusy(() -> {
                ResponseException responseException = expectThrows(ResponseException.class, () -> client.performRequest(newVersionRequest));
                assertThat(responseException.getResponse().getStatusLine().getStatusCode(),
                    equalTo(RestStatus.INTERNAL_SERVER_ERROR.getStatus()));
                assertThat(responseException.getMessage(),
                    containsString("{\"error\":{\"root_cause\":[],\"type\":\"search_phase_execution_exception\""));
                assertThat(responseException.getMessage(), containsString("caused_by\":{\"type\":\"version_mismatch_exception\","
                    + "\"reason\":\"One of the shards is incompatible with the required minimum version [" + newVersion + "]\""));
            });
        }
    }

    public void testMinVersionAsOldVersion() throws Exception {
        try (RestClient client = buildClient(restClientSettings(),
            allNodes.stream().map(Node::getPublishAddress).toArray(HttpHost[]::new))) {
            Request oldVersionRequest = new Request("POST", index + "/_search?min_compatible_shard_node=" + bwcVersion +
                    "&ccs_minimize_roundtrips=false");
            oldVersionRequest.setJsonEntity("{\"query\":{\"match_all\":{}},\"_source\":false}");
            assertBusy(() -> {
                assertWithBwcVersionCheck(() -> {
                    Response response = client.performRequest(oldVersionRequest);
                    ObjectPath responseObject = ObjectPath.createFromResponse(response);
                    Map<String, Object> shardsResult = responseObject.evaluate("_shards");
                    assertThat(shardsResult.get("total"), equalTo(numShards));
                    assertThat(shardsResult.get("successful"), equalTo(numShards));
                    assertThat(shardsResult.get("failed"), equalTo(0));
                    Map<String, Object> hitsResult = responseObject.evaluate("hits.total");
                    assertThat(hitsResult.get("value"), equalTo(numDocs));
                    assertThat(hitsResult.get("relation"), equalTo("eq"));
                }, client, oldVersionRequest);
            });
        }
    }

    public void testCcsMinimizeRoundtripsIsFalse() throws Exception {
        try (RestClient client = buildClient(restClientSettings(),
            allNodes.stream().map(Node::getPublishAddress).toArray(HttpHost[]::new))) {
            Version version = randomBoolean() ? newVersion : bwcVersion;
            boolean shouldSetCcsMinimizeRoundtrips = randomBoolean();

            Request request = new Request("POST", index + "/_search?min_compatible_shard_node=" + version +
                (shouldSetCcsMinimizeRoundtrips ? "&ccs_minimize_roundtrips=true" : ""));
            assertBusy(() -> {
                assertWithBwcVersionCheck(() -> {
                    ResponseException responseException = expectThrows(ResponseException.class, () -> client.performRequest(request));
                    assertThat(responseException.getResponse().getStatusLine().getStatusCode(),
                        equalTo(RestStatus.BAD_REQUEST.getStatus()));
                    assertThat(responseException.getMessage(),
                        containsString("{\"error\":{\"root_cause\":[{\"type\":\"action_request_validation_exception\""));
                    assertThat(responseException.getMessage(), containsString("\"reason\":\"Validation Failed: 1: "
                        + "[ccs_minimize_roundtrips] cannot be [true] when setting a minimum compatible shard version;\""));
                }, client, request);
            });
        }
    }

    private void assertWithBwcVersionCheck(CheckedRunnable<Exception> code, RestClient client, Request request) throws Exception {
        if (bwcVersion.before(Version.V_8_0_0)) {
            // min_compatible_shard_node support doesn't exist in older versions and there will be an "unrecognized parameter" exception
            ResponseException exception = expectThrows(ResponseException.class, () -> client.performRequest(request));
            assertThat(exception.getResponse().getStatusLine().getStatusCode(),
                equalTo(RestStatus.BAD_REQUEST.getStatus()));
            assertThat(exception.getMessage(), containsString("contains unrecognized parameter: [min_compatible_shard_node]"));
        } else {
            code.run();
        }
    }
}
