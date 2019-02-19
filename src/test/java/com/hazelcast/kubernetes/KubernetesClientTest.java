/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.kubernetes;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.hazelcast.kubernetes.KubernetesClient.Endpoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;

public class KubernetesClientTest {
    private static final String KUBERNETES_MASTER_IP = "localhost";

    private static final String TOKEN = "sample-token";
    private static final String CA_CERTIFICATE = "sample-ca-certificate";
    private static final String NAMESPACE = "sample-namespace";

    private static final String PRIVATE_IP_1 = "192.168.0.25";
    private static final String PRIVATE_IP_2 = "172.17.0.5";
    private static final String NOT_READY_PRIVATE_IP = "172.17.0.6";
    private static final String IP_1 = "35.232.226.200";
    private static final String IP_2 = "35.232.226.201";
    private static final String IP_3 = "35.232.226.202";
    private static final Integer PRIVATE_PORT_1 = 5701;
    private static final Integer PRIVATE_PORT_2 = 5702;
    private static final Integer NOT_READY_PRIVATE_PORT = 5703;
    private static final Integer PUBLIC_PORT_1 = 32123;
    private static final Integer PUBLIC_PORT_2 = 32124;
    private static final Integer NOT_READY_PUBLIC_PORT = 32125;
    private static final String PRIVATE_IP_PORT_1 = ipPort(PRIVATE_IP_1, PRIVATE_PORT_1);
    private static final String PRIVATE_IP_PORT_2 = ipPort(PRIVATE_IP_2, PRIVATE_PORT_2);
    private static final String NOT_READY_PRIVATE_IP_PORT = ipPort(NOT_READY_PRIVATE_IP, NOT_READY_PRIVATE_PORT);
    private static final String PUBLIC_IP_PORT_1 = ipPort(IP_1, PUBLIC_PORT_1);
    private static final String PUBLIC_IP_PORT_2 = ipPort(IP_2, PUBLIC_PORT_2);
    private static final String NOT_READY_PUBLIC_IP_PORT = ipPort(IP_1, NOT_READY_PUBLIC_PORT);
    private static final String POD_NAME_1 = "my-release-hazelcast-0";
    private static final String POD_NAME_2 = "my-release-hazelcast-1";
    private static final String NOT_READY_POD_NAME = "my-release-hazelcast-2";
    private static final String SERVICE_NAME_1 = "hazelcast-service-0";
    private static final String SERVICE_NAME_2 = "hazelcast-service-1";
    private static final String NOT_READY_SERVICE_NAME = "hazelcast-service-2";
    private static final String ZONE = "us-central1-a";
    private static final String NODE_NAME_1 = "gke-rafal-test-cluster-default-pool-9238654c-12tz";
    private static final String NODE_NAME_2 = "gke-rafal-test-cluster-default-pool-123456c-12tz";

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort());

    private KubernetesClient kubernetesClient;

    @Before
    public void setUp() {
        String kubernetesMasterUrl = String.format("http://%s:%d", KUBERNETES_MASTER_IP, wireMockRule.port());
        kubernetesClient = new KubernetesClient(NAMESPACE, kubernetesMasterUrl, TOKEN, CA_CERTIFICATE);
        stubFor(get(urlMatching("/api/.*")).atPriority(5).willReturn(aResponse().withStatus(401)));
    }

    @Test
    public void endpointsByNamespace() {
        // given
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/pods", NAMESPACE)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(podsListBody())));

        // when
        List<Endpoint> result = kubernetesClient.endpoints();

        // then
        assertThat(extractPrivateIpPortIsReady(result),
                containsInAnyOrder(ready(PRIVATE_IP_PORT_1), ready(PRIVATE_IP_PORT_2), notReady(NOT_READY_PRIVATE_IP)));
    }

    private String ready(String ipPort) {
        return String.format("%s:%s", ipPort, true);
    }

    private String notReady(String ipPort) {
        return String.format("%s:%s", ipPort, false);
    }

    @Test
    public void endpointsByNamespaceWithLoadBalancerPublicIp() {
        // given
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/pods", NAMESPACE)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(podsListBodyPublicIp())));
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/endpoints", NAMESPACE)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(endpointsListBodyPublicIp())));
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/services/%s", NAMESPACE, SERVICE_NAME_1)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(
                        aResponse().withStatus(200)
                                   .withBody(serviceBodyLoadBalancerPublicIp(SERVICE_NAME_1, IP_1, PUBLIC_PORT_1.toString()))));
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/services/%s", NAMESPACE, SERVICE_NAME_2)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(
                        aResponse().withStatus(200)
                                   .withBody(serviceBodyLoadBalancerPublicIp(SERVICE_NAME_2, IP_2, PUBLIC_PORT_2.toString()))));
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/services/%s", NAMESPACE, NOT_READY_SERVICE_NAME)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200)
                                       .withBody(serviceBodyLoadBalancerPublicIp(NOT_READY_SERVICE_NAME, IP_3,
                                               NOT_READY_PUBLIC_PORT.toString()))));

        // when
        List<Endpoint> result = kubernetesClient.endpoints();

        // then
        assertThat(extractPrivateIpPortIsReady(result),
                containsInAnyOrder(ready(PRIVATE_IP_PORT_1), ready(PRIVATE_IP_PORT_2), notReady(NOT_READY_PRIVATE_IP_PORT)));
        assertThat(extractPublicIpPortIsReady(result), containsInAnyOrder(ready(PUBLIC_IP_PORT_1), ready(PUBLIC_IP_PORT_2),
                notReady(ipPort(IP_3, NOT_READY_PUBLIC_PORT))));
    }

    @Test
    public void endpointsByNamespaceWithNodePublicIp() {
        // given
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/pods", NAMESPACE)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(podsListBodyPublicIp())));
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/endpoints", NAMESPACE)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(endpointsListBodyPublicIp())));
        stubFor(get(urlEqualTo(String.format("/api/v1/nodes/%s", NODE_NAME_1)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(nodeBodyPublicIp(NODE_NAME_1, IP_1))));
        stubFor(get(urlEqualTo(String.format("/api/v1/nodes/%s", NODE_NAME_2)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(nodeBodyPublicIp(NODE_NAME_2, IP_2))));
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/services/%s", NAMESPACE, SERVICE_NAME_1)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(
                        aResponse().withStatus(200).withBody(serviceBodyNodePublicIp(SERVICE_NAME_1, PUBLIC_PORT_1.toString()))));
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/services/%s", NAMESPACE, SERVICE_NAME_2)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(
                        aResponse().withStatus(200).withBody(serviceBodyNodePublicIp(SERVICE_NAME_2, PUBLIC_PORT_2.toString()))));
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/services/%s", NAMESPACE, NOT_READY_SERVICE_NAME)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200)
                                       .withBody(serviceBodyNodePublicIp(NOT_READY_SERVICE_NAME,
                                               NOT_READY_PUBLIC_PORT.toString()))));

        // when
        List<Endpoint> result = kubernetesClient.endpoints();

        // then
        assertThat(extractPrivateIpPortIsReady(result),
                containsInAnyOrder(ready(PRIVATE_IP_PORT_1), ready(PRIVATE_IP_PORT_2), notReady(NOT_READY_PRIVATE_IP_PORT)));
        assertThat(extractPublicIpPortIsReady(result), containsInAnyOrder(ready(PUBLIC_IP_PORT_1), ready(PUBLIC_IP_PORT_2),
                notReady(NOT_READY_PUBLIC_IP_PORT)));
    }

    @Test
    public void endpointsByNamespaceAndLabel() {
        // given
        String serviceLabel = "sample-service-label";
        String serviceLabelValue = "sample-service-label-value";
        stubFor(get(urlPathMatching(String.format("/api/v1/namespaces/%s/endpoints", NAMESPACE)))
                .withQueryParam("labelSelector", equalTo(String.format("%s=%s", serviceLabel, serviceLabelValue)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(endpointsListBody())));

        // when
        List<Endpoint> result = kubernetesClient.endpointsByLabel(serviceLabel, serviceLabelValue);

        // then
        assertThat(extractPrivateIpPortIsReady(result),
                containsInAnyOrder(ready(PRIVATE_IP_PORT_1), ready(PRIVATE_IP_PORT_2), notReady(NOT_READY_PRIVATE_IP_PORT)));

    }

    @Test
    public void endpointsByNamespaceAndServiceName() {
        // given
        String serviceName = "service-name";
        stubFor(get(urlPathMatching(String.format("/api/v1/namespaces/%s/endpoints/%s", NAMESPACE, serviceName)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(endpointsBody())));

        // when
        List<Endpoint> result = kubernetesClient.endpointsByName(serviceName);

        // then
        assertThat(extractPrivateIpPortIsReady(result), containsInAnyOrder(ready(PRIVATE_IP_PORT_1), ready(PRIVATE_IP_PORT_2)));
    }

    @Test
    public void zoneBeta() {
        // given
        stubFor(get(urlPathMatching(String.format("/api/v1/namespaces/%s/pods/%s", NAMESPACE, POD_NAME_1)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(podBody(NODE_NAME_1))));
        stubFor(get(urlPathMatching(String.format("/api/v1/nodes/%s", NODE_NAME_1)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(nodeBetaBody())));

        // when
        String zone = kubernetesClient.zone(POD_NAME_1);

        // then
        assertEquals(ZONE, zone);
    }

    @Test
    public void zone() {
        // given
        String nodeName = "gke-rafal-test-cluster-default-pool-9238654c-12tz";
        stubFor(get(urlPathMatching(String.format("/api/v1/namespaces/%s/pods/%s", NAMESPACE, POD_NAME_1)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(podBody(nodeName))));
        stubFor(get(urlPathMatching(String.format("/api/v1/nodes/%s", nodeName)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(nodeBody())));

        // when
        String zone = kubernetesClient.zone(POD_NAME_1);

        // then
        assertEquals(ZONE, zone);
    }

    @Test
    public void publicIp() {

    }

    @Test(expected = KubernetesClientException.class)
    public void forbidden() {
        // given
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/pods", NAMESPACE)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(501).withBody(forbiddenBody())));

        // when
        kubernetesClient.endpoints();
    }

    @Test(expected = KubernetesClientException.class)
    public void malformedResponse() {
        // given
        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/pods", NAMESPACE)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(malformedBody())));

        // when
        kubernetesClient.endpoints();
    }

    @Test(expected = KubernetesClientException.class)
    public void nullToken() {
        // given
        String kubernetesMasterUrl = String.format("http://%s:%d", KUBERNETES_MASTER_IP, wireMockRule.port());
        KubernetesClient kubernetesClient = new KubernetesClient(NAMESPACE, kubernetesMasterUrl, TOKEN, null);

        stubFor(get(urlEqualTo(String.format("/api/v1/namespaces/%s/pods", NAMESPACE)))
                .withHeader("Authorization", equalTo(String.format("Bearer %s", TOKEN)))
                .willReturn(aResponse().withStatus(200).withBody(malformedBody())));

        // when
        kubernetesClient.endpoints();
    }

    /**
     * Real response recorded from the Kubernetes API call "/api/v1/namespaces/{namespace}/pods".
     */
    private static String podsListBody() {
        return String.format("{\n"
                + "  \"kind\": \"PodList\",\n"
                + "  \"apiVersion\": \"v1\",\n"
                + "  \"metadata\": {\n"
                + "    \"selfLink\": \"/api/v1/namespaces/default/pods\",\n"
                + "    \"resourceVersion\": \"4400\"\n"
                + "  },\n"
                + "  \"items\": [\n"
                + "    {\n"
                + "      \"metadata\": {\n"
                + "        \"name\": \"%s\",\n"
                + "        \"generateName\": \"my-release-hazelcast-\",\n"
                + "        \"namespace\": \"default\",\n"
                + "        \"selfLink\": \"/api/v1/namespaces/default/pods/my-release-hazelcast-0\",\n"
                + "        \"uid\": \"21b91e5b-eefd-11e8-ab27-42010a8001ce\",\n"
                + "        \"resourceVersion\": \"1967\",\n"
                + "        \"creationTimestamp\": \"2018-11-23T08:52:39Z\",\n"
                + "        \"labels\": {\n"
                + "          \"app\": \"hazelcast\",\n"
                + "          \"controller-revision-hash\": \"my-release-hazelcast-7bcf66dc79\",\n"
                + "          \"release\": \"my-release\",\n"
                + "          \"role\": \"hazelcast\",\n"
                + "          \"statefulset.kubernetes.io/pod-name\": \"my-release-hazelcast-0\"\n"
                + "        },\n"
                + "        \"annotations\": {\n"
                + "          \"kubernetes.io/limit-ranger\": \"LimitRanger plugin set: cpu request for container my-release-hazelcast\"\n"
                + "        },\n"
                + "        \"ownerReferences\": [\n"
                + "          {\n"
                + "            \"apiVersion\": \"apps/v1beta1\",\n"
                + "            \"kind\": \"StatefulSet\",\n"
                + "            \"name\": \"my-release-hazelcast\",\n"
                + "            \"uid\": \"21b3fb7f-eefd-11e8-ab27-42010a8001ce\",\n"
                + "            \"controller\": true,\n"
                + "            \"blockOwnerDeletion\": true\n"
                + "          }\n"
                + "        ]\n"
                + "      },\n"
                + "      \"spec\": {\n"
                + "        \"volumes\": [\n"
                + "          {\n"
                + "            \"name\": \"hazelcast-storage\",\n"
                + "            \"configMap\": {\n"
                + "              \"name\": \"my-release-hazelcast-configuration\",\n"
                + "              \"defaultMode\": 420\n"
                + "            }\n"
                + "          },\n"
                + "          {\n"
                + "            \"name\": \"my-release-hazelcast-token-j9db4\",\n"
                + "            \"secret\": {\n"
                + "              \"secretName\": \"my-release-hazelcast-token-j9db4\",\n"
                + "              \"defaultMode\": 420\n"
                + "            }\n"
                + "          }\n"
                + "        ],\n"
                + "        \"containers\": [\n"
                + "          {\n"
                + "            \"name\": \"my-release-hazelcast\",\n"
                + "            \"image\": \"hazelcast/hazelcast:latest\",\n"
                + "            \"ports\": [\n"
                + "              {\n"
                + "                \"name\": \"hazelcast\",\n"
                + "                \"containerPort\": %s,\n"
                + "                \"protocol\": \"TCP\"\n"
                + "              }\n"
                + "            ],\n"
                + "            \"resources\": {\n"
                + "              \"requests\": {\n"
                + "                \"cpu\": \"100m\"\n"
                + "              }\n"
                + "            },\n"
                + "            \"volumeMounts\": [\n"
                + "              {\n"
                + "                \"name\": \"hazelcast-storage\",\n"
                + "                \"mountPath\": \"/data/hazelcast\"\n"
                + "              },\n"
                + "              {\n"
                + "                \"name\": \"my-release-hazelcast-token-j9db4\",\n"
                + "                \"readOnly\": true,\n"
                + "                \"mountPath\": \"/var/run/secrets/kubernetes.io/serviceaccount\"\n"
                + "              }\n"
                + "            ],\n"
                + "            \"terminationMessagePath\": \"/dev/termination-log\",\n"
                + "            \"terminationMessagePolicy\": \"File\",\n"
                + "            \"imagePullPolicy\": \"Always\"\n"
                + "          }\n"
                + "        ],\n"
                + "        \"restartPolicy\": \"Always\",\n"
                + "        \"terminationGracePeriodSeconds\": 30,\n"
                + "        \"dnsPolicy\": \"ClusterFirst\",\n"
                + "        \"serviceAccountName\": \"my-release-hazelcast\",\n"
                + "        \"serviceAccount\": \"my-release-hazelcast\",\n"
                + "        \"nodeName\": \"gke-rafal-test-cluster-default-pool-e5fb2ea5-c7g8\",\n"
                + "        \"securityContext\": {\n"
                + "\n"
                + "        },\n"
                + "        \"hostname\": \"my-release-hazelcast-0\",\n"
                + "        \"schedulerName\": \"default-scheduler\",\n"
                + "        \"tolerations\": [\n"
                + "          {\n"
                + "            \"key\": \"node.kubernetes.io/not-ready\",\n"
                + "            \"operator\": \"Exists\",\n"
                + "            \"effect\": \"NoExecute\",\n"
                + "            \"tolerationSeconds\": 300\n"
                + "          },\n"
                + "          {\n"
                + "            \"key\": \"node.kubernetes.io/unreachable\",\n"
                + "            \"operator\": \"Exists\",\n"
                + "            \"effect\": \"NoExecute\",\n"
                + "            \"tolerationSeconds\": 300\n"
                + "          }\n"
                + "        ]\n"
                + "      },\n"
                + "      \"status\": {\n"
                + "        \"phase\": \"Running\",\n"
                + "        \"conditions\": [\n"
                + "          {\n"
                + "            \"type\": \"Initialized\",\n"
                + "            \"status\": \"True\",\n"
                + "            \"lastProbeTime\": null,\n"
                + "            \"lastTransitionTime\": \"2018-11-23T08:52:39Z\"\n"
                + "          },\n"
                + "          {\n"
                + "            \"type\": \"Ready\",\n"
                + "            \"status\": \"True\",\n"
                + "            \"lastProbeTime\": null,\n"
                + "            \"lastTransitionTime\": \"2018-11-23T08:53:21Z\"\n"
                + "          },\n"
                + "          {\n"
                + "            \"type\": \"PodScheduled\",\n"
                + "            \"status\": \"True\",\n"
                + "            \"lastProbeTime\": null,\n"
                + "            \"lastTransitionTime\": \"2018-11-23T08:52:39Z\"\n"
                + "          }\n"
                + "        ],\n"
                + "        \"hostIP\": \"10.240.0.18\",\n"
                + "        \"podIP\": \"%s\",\n"
                + "        \"startTime\": \"2018-11-23T08:52:39Z\",\n"
                + "        \"containerStatuses\": [\n"
                + "          {\n"
                + "            \"name\": \"my-release-hazelcast\",\n"
                + "            \"state\": {\n"
                + "              \"running\": {\n"
                + "                \"startedAt\": \"2018-11-23T08:52:47Z\"\n"
                + "              }\n"
                + "            },\n"
                + "            \"lastState\": {\n"
                + "\n"
                + "            },\n"
                + "            \"ready\": true,\n"
                + "            \"restartCount\": 0,\n"
                + "            \"image\": \"hazelcast/hazelcast:latest\",\n"
                + "            \"imageID\": \"docker-pullable://hazelcast/hazelcast@sha256:a4dd478dc792ba3fa560aa41b107fed676b37c283be0306303544a0a8ebcc4c8\",\n"
                + "            \"containerID\": \"docker://d2c59dd02561ae2d274dfa0413277422383241425ce5701ca36c30a862d1520a\"\n"
                + "          }\n"
                + "        ],\n"
                + "        \"qosClass\": \"Burstable\"\n"
                + "      }\n"
                + "    },\n"
                + "    {\n"
                + "      \"metadata\": {\n"
                + "        \"name\": \"%s\",\n"
                + "        \"generateName\": \"my-release-hazelcast-\",\n"
                + "        \"namespace\": \"default\",\n"
                + "        \"selfLink\": \"/api/v1/namespaces/default/pods/my-release-hazelcast-1\",\n"
                + "        \"uid\": \"3a7fd73f-eefd-11e8-ab27-42010a8001ce\",\n"
                + "        \"resourceVersion\": \"2022\",\n"
                + "        \"creationTimestamp\": \"2018-11-23T08:53:21Z\",\n"
                + "        \"labels\": {\n"
                + "          \"role\": \"hazelcast\",\n"
                + "          \"statefulset.kubernetes.io/pod-name\": \"my-release-hazelcast-1\",\n"
                + "          \"app\": \"hazelcast\",\n"
                + "          \"controller-revision-hash\": \"my-release-hazelcast-7bcf66dc79\",\n"
                + "          \"release\": \"my-release\"\n"
                + "        },\n"
                + "        \"annotations\": {\n"
                + "          \"kubernetes.io/limit-ranger\": \"LimitRanger plugin set: cpu request for container my-release-hazelcast\"\n"
                + "        },\n"
                + "        \"ownerReferences\": [\n"
                + "          {\n"
                + "            \"apiVersion\": \"apps/v1beta1\",\n"
                + "            \"kind\": \"StatefulSet\",\n"
                + "            \"name\": \"my-release-hazelcast\",\n"
                + "            \"uid\": \"21b3fb7f-eefd-11e8-ab27-42010a8001ce\",\n"
                + "            \"controller\": true,\n"
                + "            \"blockOwnerDeletion\": true\n"
                + "          }\n"
                + "        ]\n"
                + "      },\n"
                + "      \"spec\": {\n"
                + "        \"volumes\": [\n"
                + "          {\n"
                + "            \"name\": \"hazelcast-storage\",\n"
                + "            \"configMap\": {\n"
                + "              \"name\": \"my-release-hazelcast-configuration\",\n"
                + "              \"defaultMode\": 420\n"
                + "            }\n"
                + "          },\n"
                + "          {\n"
                + "            \"name\": \"my-release-hazelcast-token-j9db4\",\n"
                + "            \"secret\": {\n"
                + "              \"secretName\": \"my-release-hazelcast-token-j9db4\",\n"
                + "              \"defaultMode\": 420\n"
                + "            }\n"
                + "          }\n"
                + "        ],\n"
                + "        \"containers\": [\n"
                + "          {\n"
                + "            \"name\": \"my-release-hazelcast\",\n"
                + "            \"image\": \"hazelcast/hazelcast:latest\",\n"
                + "            \"ports\": [\n"
                + "              {\n"
                + "                \"name\": \"hazelcast\",\n"
                + "                \"containerPort\": %s,\n"
                + "                \"protocol\": \"TCP\"\n"
                + "              }\n"
                + "            ],\n"
                + "            \"resources\": {\n"
                + "              \"requests\": {\n"
                + "                \"cpu\": \"100m\"\n"
                + "              }\n"
                + "            },\n"
                + "            \"volumeMounts\": [\n"
                + "              {\n"
                + "                \"name\": \"hazelcast-storage\",\n"
                + "                \"mountPath\": \"/data/hazelcast\"\n"
                + "              },\n"
                + "              {\n"
                + "                \"name\": \"my-release-hazelcast-token-j9db4\",\n"
                + "                \"readOnly\": true,\n"
                + "                \"mountPath\": \"/var/run/secrets/kubernetes.io/serviceaccount\"\n"
                + "              }\n"
                + "            ],\n"
                + "            \"terminationMessagePath\": \"/dev/termination-log\",\n"
                + "            \"terminationMessagePolicy\": \"File\",\n"
                + "            \"imagePullPolicy\": \"Always\"\n"
                + "          }\n"
                + "        ],\n"
                + "        \"restartPolicy\": \"Always\",\n"
                + "        \"terminationGracePeriodSeconds\": 30,\n"
                + "        \"dnsPolicy\": \"ClusterFirst\",\n"
                + "        \"serviceAccountName\": \"my-release-hazelcast\",\n"
                + "        \"serviceAccount\": \"my-release-hazelcast\",\n"
                + "        \"nodeName\": \"gke-rafal-test-cluster-default-pool-e5fb2ea5-c7g8\",\n"
                + "        \"securityContext\": {\n"
                + "\n"
                + "        },\n"
                + "        \"hostname\": \"my-release-hazelcast-1\",\n"
                + "        \"schedulerName\": \"default-scheduler\",\n"
                + "        \"tolerations\": [\n"
                + "          {\n"
                + "            \"key\": \"node.kubernetes.io/not-ready\",\n"
                + "            \"operator\": \"Exists\",\n"
                + "            \"effect\": \"NoExecute\",\n"
                + "            \"tolerationSeconds\": 300\n"
                + "          },\n"
                + "          {\n"
                + "            \"key\": \"node.kubernetes.io/unreachable\",\n"
                + "            \"operator\": \"Exists\",\n"
                + "            \"effect\": \"NoExecute\",\n"
                + "            \"tolerationSeconds\": 300\n"
                + "          }\n"
                + "        ]\n"
                + "      },\n"
                + "      \"status\": {\n"
                + "        \"phase\": \"Running\",\n"
                + "        \"conditions\": [\n"
                + "          {\n"
                + "            \"type\": \"Initialized\",\n"
                + "            \"status\": \"True\",\n"
                + "            \"lastProbeTime\": null,\n"
                + "            \"lastTransitionTime\": \"2018-11-23T08:53:21Z\"\n"
                + "          },\n"
                + "          {\n"
                + "            \"type\": \"Ready\",\n"
                + "            \"status\": \"True\",\n"
                + "            \"lastProbeTime\": null,\n"
                + "            \"lastTransitionTime\": \"2018-11-23T08:53:55Z\"\n"
                + "          },\n"
                + "          {\n"
                + "            \"type\": \"PodScheduled\",\n"
                + "            \"status\": \"True\",\n"
                + "            \"lastProbeTime\": null,\n"
                + "            \"lastTransitionTime\": \"2018-11-23T08:53:21Z\"\n"
                + "          }\n"
                + "        ],\n"
                + "        \"hostIP\": \"10.240.0.18\",\n"
                + "        \"podIP\": \"%s\",\n"
                + "        \"startTime\": \"2018-11-23T08:53:21Z\",\n"
                + "        \"containerStatuses\": [\n"
                + "          {\n"
                + "            \"name\": \"my-release-hazelcast\",\n"
                + "            \"state\": {\n"
                + "              \"running\": {\n"
                + "                \"startedAt\": \"2018-11-23T08:53:23Z\"\n"
                + "              }\n"
                + "            },\n"
                + "            \"lastState\": {\n"
                + "\n"
                + "            },\n"
                + "            \"ready\": true,\n"
                + "            \"restartCount\": 0,\n"
                + "            \"image\": \"hazelcast/hazelcast:latest\",\n"
                + "            \"imageID\": \"docker-pullable://hazelcast/hazelcast@sha256:a4dd478dc792ba3fa560aa41b107fed676b37c283be0306303544a0a8ebcc4c8\",\n"
                + "            \"containerID\": \"docker://705df57d5bfb1417683800aad2b8ac38ba68abcfaf03550d797e36e79313c903\"\n"
                + "          }\n"
                + "        ],\n"
                + "        \"qosClass\": \"Burstable\"\n"
                + "      }\n"
                + "    },\n"
                + "    {\n"
                + "      \"metadata\": {\n"
                + "        \"name\": \"my-release-hazelcast-mancenter-f54949c7f-k8vx8\",\n"
                + "        \"generateName\": \"my-release-hazelcast-mancenter-f54949c7f-\",\n"
                + "        \"namespace\": \"default\",\n"
                + "        \"selfLink\": \"/api/v1/namespaces/default/pods/my-release-hazelcast-mancenter-f54949c7f-k8vx8\",\n"
                + "        \"uid\": \"21b7e41c-eefd-11e8-ab27-42010a8001ce\",\n"
                + "        \"resourceVersion\": \"2025\",\n"
                + "        \"creationTimestamp\": \"2018-11-23T08:52:39Z\",\n"
                + "        \"labels\": {\n"
                + "          \"app\": \"hazelcast\",\n"
                + "          \"pod-template-hash\": \"910505739\",\n"
                + "          \"release\": \"my-release\",\n"
                + "          \"role\": \"mancenter\"\n"
                + "        },\n"
                + "        \"annotations\": {\n"
                + "          \"kubernetes.io/limit-ranger\": \"LimitRanger plugin set: cpu request for container my-release-hazelcast-mancenter\"\n"
                + "        },\n"
                + "        \"ownerReferences\": [\n"
                + "          {\n"
                + "            \"apiVersion\": \"extensions/v1beta1\",\n"
                + "            \"kind\": \"ReplicaSet\",\n"
                + "            \"name\": \"my-release-hazelcast-mancenter-f54949c7f\",\n"
                + "            \"uid\": \"21b450e8-eefd-11e8-ab27-42010a8001ce\",\n"
                + "            \"controller\": true,\n"
                + "            \"blockOwnerDeletion\": true\n"
                + "          }\n"
                + "        ]\n"
                + "      },\n"
                + "      \"spec\": {\n"
                + "        \"volumes\": [\n"
                + "          {\n"
                + "            \"name\": \"mancenter-storage\",\n"
                + "            \"persistentVolumeClaim\": {\n"
                + "              \"claimName\": \"my-release-hazelcast-mancenter\"\n"
                + "            }\n"
                + "          },\n"
                + "          {\n"
                + "            \"name\": \"default-token-cvdgc\",\n"
                + "            \"secret\": {\n"
                + "              \"secretName\": \"default-token-cvdgc\",\n"
                + "              \"defaultMode\": 420\n"
                + "            }\n"
                + "          }\n"
                + "        ],\n"
                + "        \"containers\": [\n"
                + "          {\n"
                + "            \"name\": \"my-release-hazelcast\",\n"
                + "            \"image\": \"hazelcast/hazelcast:latest\",\n"
                + "            \"ports\": [\n"
                + "              {\n"
                + "                \"name\": \"hazelcast\",\n"
                + "                \"protocol\": \"TCP\"\n"
                + "              }\n"
                + "            ],\n"
                + "            \"resources\": {\n"
                + "              \"requests\": {\n"
                + "                \"cpu\": \"100m\"\n"
                + "              }\n"
                + "            },\n"
                + "            \"terminationMessagePath\": \"/dev/termination-log\",\n"
                + "            \"terminationMessagePolicy\": \"File\",\n"
                + "            \"imagePullPolicy\": \"Always\"\n"
                + "          }\n"
                + "        ],\n"
                + "        \"restartPolicy\": \"Always\",\n"
                + "        \"terminationGracePeriodSeconds\": 30,\n"
                + "        \"dnsPolicy\": \"ClusterFirst\",\n"
                + "        \"serviceAccountName\": \"default\",\n"
                + "        \"serviceAccount\": \"default\",\n"
                + "        \"nodeName\": \"gke-rafal-test-cluster-default-pool-e5fb2ea5-c7g8\",\n"
                + "        \"securityContext\": {\n"
                + "          \"runAsUser\": 0\n"
                + "        },\n"
                + "        \"schedulerName\": \"default-scheduler\",\n"
                + "        \"tolerations\": [\n"
                + "          {\n"
                + "            \"key\": \"node.kubernetes.io/not-ready\",\n"
                + "            \"operator\": \"Exists\",\n"
                + "            \"effect\": \"NoExecute\",\n"
                + "            \"tolerationSeconds\": 300\n"
                + "          },\n"
                + "          {\n"
                + "            \"key\": \"node.kubernetes.io/unreachable\",\n"
                + "            \"operator\": \"Exists\",\n"
                + "            \"effect\": \"NoExecute\",\n"
                + "            \"tolerationSeconds\": 300\n"
                + "          }\n"
                + "        ]\n"
                + "      },\n"
                + "      \"status\": {\n"
                + "        \"phase\": \"Running\",\n"
                + "        \"conditions\": [\n"
                + "          {\n"
                + "            \"type\": \"Initialized\",\n"
                + "            \"status\": \"True\",\n"
                + "            \"lastProbeTime\": null,\n"
                + "            \"lastTransitionTime\": \"2018-11-23T08:52:46Z\"\n"
                + "          },\n"
                + "          {\n"
                + "            \"type\": \"Ready\",\n"
                + "            \"status\": \"True\",\n"
                + "            \"lastProbeTime\": null,\n"
                + "            \"lastTransitionTime\": \"2018-11-23T08:53:55Z\"\n"
                + "          },\n"
                + "          {\n"
                + "            \"type\": \"PodScheduled\",\n"
                + "            \"status\": \"True\",\n"
                + "            \"lastProbeTime\": null,\n"
                + "            \"lastTransitionTime\": \"2018-11-23T08:52:46Z\"\n"
                + "          }\n"
                + "        ],\n"
                + "        \"hostIP\": \"10.240.0.18\",\n"
                + "        \"podIP\": \"%s\",\n"
                + "        \"startTime\": \"2018-11-23T08:52:46Z\",\n"
                + "        \"containerStatuses\": [\n"
                + "          {\n"
                + "            \"name\": \"my-release-hazelcast-mancenter\",\n"
                + "            \"state\": {\n"
                + "              \"running\": {\n"
                + "                \"startedAt\": \"2018-11-23T08:53:08Z\"\n"
                + "              }\n"
                + "            },\n"
                + "            \"lastState\": {\n"
                + "\n"
                + "            },\n"
                + "            \"ready\": false,\n"
                + "            \"restartCount\": 0,\n"
                + "            \"image\": \"hazelcast/management-center:latest\",\n"
                + "            \"imageID\": \"docker-pullable://hazelcast/management-center@sha256:0427778a84476a7b11b248b1720d22e43ee689f5148506ea532491aad1a91afa\",\n"
                + "            \"containerID\": \"docker://99ac6e5204876a3106b01d6b3ab2466b1d63c9bd1621d86d7e984840b2c44a3f\"\n"
                + "          }\n"
                + "        ],\n"
                + "        \"qosClass\": \"Burstable\"\n"
                + "      }\n"
                + "    }\n"
                + "  ]\n"
                + "}", POD_NAME_1, PRIVATE_PORT_1, PRIVATE_IP_1, POD_NAME_2, PRIVATE_PORT_2, PRIVATE_IP_2, NOT_READY_PRIVATE_IP);
    }

    /**
     * Real response recorded from the Kubernetes API call "/api/v1/namespaces/{namespace}/pods".
     */
    private static String podsListBodyPublicIp() {
        return String.format("{\n"
                        + "  \"kind\": \"PodList\",\n"
                        + "  \"apiVersion\": \"v1\",\n"
                        + "  \"metadata\": {\n"
                        + "    \"selfLink\": \"/api/v1/namespaces/default/pods\",\n"
                        + "    \"resourceVersion\": \"4400\"\n"
                        + "  },\n"
                        + "  \"items\": [\n"
                        + "    {\n"
                        + "      \"metadata\": {\n"
                        + "        \"name\": \"%s\",\n"
                        + "        \"generateName\": \"my-release-hazelcast-\",\n"
                        + "        \"namespace\": \"default\",\n"
                        + "        \"selfLink\": \"/api/v1/namespaces/default/pods/my-release-hazelcast-0\",\n"
                        + "        \"uid\": \"21b91e5b-eefd-11e8-ab27-42010a8001ce\",\n"
                        + "        \"resourceVersion\": \"1967\",\n"
                        + "        \"creationTimestamp\": \"2018-11-23T08:52:39Z\",\n"
                        + "        \"labels\": {\n"
                        + "          \"app\": \"hazelcast\",\n"
                        + "          \"controller-revision-hash\": \"my-release-hazelcast-7bcf66dc79\",\n"
                        + "          \"release\": \"my-release\",\n"
                        + "          \"role\": \"hazelcast\",\n"
                        + "          \"statefulset.kubernetes.io/pod-name\": \"my-release-hazelcast-0\"\n"
                        + "        },\n"
                        + "        \"annotations\": {\n"
                        + "          \"kubernetes.io/limit-ranger\": \"LimitRanger plugin set: cpu request for container my-release-hazelcast\"\n"
                        + "        },\n"
                        + "        \"ownerReferences\": [\n"
                        + "          {\n"
                        + "            \"apiVersion\": \"apps/v1beta1\",\n"
                        + "            \"kind\": \"StatefulSet\",\n"
                        + "            \"name\": \"my-release-hazelcast\",\n"
                        + "            \"uid\": \"21b3fb7f-eefd-11e8-ab27-42010a8001ce\",\n"
                        + "            \"controller\": true,\n"
                        + "            \"blockOwnerDeletion\": true\n"
                        + "          }\n"
                        + "        ]\n"
                        + "      },\n"
                        + "      \"spec\": {\n"
                        + "        \"volumes\": [\n"
                        + "          {\n"
                        + "            \"name\": \"hazelcast-storage\",\n"
                        + "            \"configMap\": {\n"
                        + "              \"name\": \"my-release-hazelcast-configuration\",\n"
                        + "              \"defaultMode\": 420\n"
                        + "            }\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"name\": \"my-release-hazelcast-token-j9db4\",\n"
                        + "            \"secret\": {\n"
                        + "              \"secretName\": \"my-release-hazelcast-token-j9db4\",\n"
                        + "              \"defaultMode\": 420\n"
                        + "            }\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"containers\": [\n"
                        + "          {\n"
                        + "            \"name\": \"my-release-hazelcast\",\n"
                        + "            \"image\": \"hazelcast/hazelcast:latest\",\n"
                        + "            \"ports\": [\n"
                        + "              {\n"
                        + "                \"name\": \"hazelcast\",\n"
                        + "                \"containerPort\": %s,\n"
                        + "                \"protocol\": \"TCP\"\n"
                        + "              }\n"
                        + "            ],\n"
                        + "            \"resources\": {\n"
                        + "              \"requests\": {\n"
                        + "                \"cpu\": \"100m\"\n"
                        + "              }\n"
                        + "            },\n"
                        + "            \"volumeMounts\": [\n"
                        + "              {\n"
                        + "                \"name\": \"hazelcast-storage\",\n"
                        + "                \"mountPath\": \"/data/hazelcast\"\n"
                        + "              },\n"
                        + "              {\n"
                        + "                \"name\": \"my-release-hazelcast-token-j9db4\",\n"
                        + "                \"readOnly\": true,\n"
                        + "                \"mountPath\": \"/var/run/secrets/kubernetes.io/serviceaccount\"\n"
                        + "              }\n"
                        + "            ],\n"
                        + "            \"terminationMessagePath\": \"/dev/termination-log\",\n"
                        + "            \"terminationMessagePolicy\": \"File\",\n"
                        + "            \"imagePullPolicy\": \"Always\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"restartPolicy\": \"Always\",\n"
                        + "        \"terminationGracePeriodSeconds\": 30,\n"
                        + "        \"dnsPolicy\": \"ClusterFirst\",\n"
                        + "        \"serviceAccountName\": \"my-release-hazelcast\",\n"
                        + "        \"serviceAccount\": \"my-release-hazelcast\",\n"
                        + "        \"nodeName\": \"gke-rafal-test-cluster-default-pool-e5fb2ea5-c7g8\",\n"
                        + "        \"securityContext\": {\n"
                        + "\n"
                        + "        },\n"
                        + "        \"hostname\": \"my-release-hazelcast-0\",\n"
                        + "        \"schedulerName\": \"default-scheduler\",\n"
                        + "        \"tolerations\": [\n"
                        + "          {\n"
                        + "            \"key\": \"node.kubernetes.io/not-ready\",\n"
                        + "            \"operator\": \"Exists\",\n"
                        + "            \"effect\": \"NoExecute\",\n"
                        + "            \"tolerationSeconds\": 300\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"key\": \"node.kubernetes.io/unreachable\",\n"
                        + "            \"operator\": \"Exists\",\n"
                        + "            \"effect\": \"NoExecute\",\n"
                        + "            \"tolerationSeconds\": 300\n"
                        + "          }\n"
                        + "        ]\n"
                        + "      },\n"
                        + "      \"status\": {\n"
                        + "        \"phase\": \"Running\",\n"
                        + "        \"conditions\": [\n"
                        + "          {\n"
                        + "            \"type\": \"Initialized\",\n"
                        + "            \"status\": \"True\",\n"
                        + "            \"lastProbeTime\": null,\n"
                        + "            \"lastTransitionTime\": \"2018-11-23T08:52:39Z\"\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"type\": \"Ready\",\n"
                        + "            \"status\": \"True\",\n"
                        + "            \"lastProbeTime\": null,\n"
                        + "            \"lastTransitionTime\": \"2018-11-23T08:53:21Z\"\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"type\": \"PodScheduled\",\n"
                        + "            \"status\": \"True\",\n"
                        + "            \"lastProbeTime\": null,\n"
                        + "            \"lastTransitionTime\": \"2018-11-23T08:52:39Z\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"hostIP\": \"10.240.0.18\",\n"
                        + "        \"podIP\": \"%s\",\n"
                        + "        \"startTime\": \"2018-11-23T08:52:39Z\",\n"
                        + "        \"containerStatuses\": [\n"
                        + "          {\n"
                        + "            \"name\": \"my-release-hazelcast\",\n"
                        + "            \"state\": {\n"
                        + "              \"running\": {\n"
                        + "                \"startedAt\": \"2018-11-23T08:52:47Z\"\n"
                        + "              }\n"
                        + "            },\n"
                        + "            \"lastState\": {\n"
                        + "\n"
                        + "            },\n"
                        + "            \"ready\": true,\n"
                        + "            \"restartCount\": 0,\n"
                        + "            \"image\": \"hazelcast/hazelcast:latest\",\n"
                        + "            \"imageID\": \"docker-pullable://hazelcast/hazelcast@sha256:a4dd478dc792ba3fa560aa41b107fed676b37c283be0306303544a0a8ebcc4c8\",\n"
                        + "            \"containerID\": \"docker://d2c59dd02561ae2d274dfa0413277422383241425ce5701ca36c30a862d1520a\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"qosClass\": \"Burstable\"\n"
                        + "      }\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"metadata\": {\n"
                        + "        \"name\": \"%s\",\n"
                        + "        \"generateName\": \"my-release-hazelcast-\",\n"
                        + "        \"namespace\": \"default\",\n"
                        + "        \"selfLink\": \"/api/v1/namespaces/default/pods/my-release-hazelcast-1\",\n"
                        + "        \"uid\": \"3a7fd73f-eefd-11e8-ab27-42010a8001ce\",\n"
                        + "        \"resourceVersion\": \"2022\",\n"
                        + "        \"creationTimestamp\": \"2018-11-23T08:53:21Z\",\n"
                        + "        \"labels\": {\n"
                        + "          \"role\": \"hazelcast\",\n"
                        + "          \"statefulset.kubernetes.io/pod-name\": \"my-release-hazelcast-1\",\n"
                        + "          \"app\": \"hazelcast\",\n"
                        + "          \"controller-revision-hash\": \"my-release-hazelcast-7bcf66dc79\",\n"
                        + "          \"release\": \"my-release\"\n"
                        + "        },\n"
                        + "        \"annotations\": {\n"
                        + "          \"kubernetes.io/limit-ranger\": \"LimitRanger plugin set: cpu request for container my-release-hazelcast\"\n"
                        + "        },\n"
                        + "        \"ownerReferences\": [\n"
                        + "          {\n"
                        + "            \"apiVersion\": \"apps/v1beta1\",\n"
                        + "            \"kind\": \"StatefulSet\",\n"
                        + "            \"name\": \"my-release-hazelcast\",\n"
                        + "            \"uid\": \"21b3fb7f-eefd-11e8-ab27-42010a8001ce\",\n"
                        + "            \"controller\": true,\n"
                        + "            \"blockOwnerDeletion\": true\n"
                        + "          }\n"
                        + "        ]\n"
                        + "      },\n"
                        + "      \"spec\": {\n"
                        + "        \"volumes\": [\n"
                        + "          {\n"
                        + "            \"name\": \"hazelcast-storage\",\n"
                        + "            \"configMap\": {\n"
                        + "              \"name\": \"my-release-hazelcast-configuration\",\n"
                        + "              \"defaultMode\": 420\n"
                        + "            }\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"name\": \"my-release-hazelcast-token-j9db4\",\n"
                        + "            \"secret\": {\n"
                        + "              \"secretName\": \"my-release-hazelcast-token-j9db4\",\n"
                        + "              \"defaultMode\": 420\n"
                        + "            }\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"containers\": [\n"
                        + "          {\n"
                        + "            \"name\": \"my-release-hazelcast\",\n"
                        + "            \"image\": \"hazelcast/hazelcast:latest\",\n"
                        + "            \"ports\": [\n"
                        + "              {\n"
                        + "                \"name\": \"hazelcast\",\n"
                        + "                \"containerPort\": %s,\n"
                        + "                \"protocol\": \"TCP\"\n"
                        + "              }\n"
                        + "            ],\n"
                        + "            \"resources\": {\n"
                        + "              \"requests\": {\n"
                        + "                \"cpu\": \"100m\"\n"
                        + "              }\n"
                        + "            },\n"
                        + "            \"volumeMounts\": [\n"
                        + "              {\n"
                        + "                \"name\": \"hazelcast-storage\",\n"
                        + "                \"mountPath\": \"/data/hazelcast\"\n"
                        + "              },\n"
                        + "              {\n"
                        + "                \"name\": \"my-release-hazelcast-token-j9db4\",\n"
                        + "                \"readOnly\": true,\n"
                        + "                \"mountPath\": \"/var/run/secrets/kubernetes.io/serviceaccount\"\n"
                        + "              }\n"
                        + "            ],\n"
                        + "            \"terminationMessagePath\": \"/dev/termination-log\",\n"
                        + "            \"terminationMessagePolicy\": \"File\",\n"
                        + "            \"imagePullPolicy\": \"Always\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"restartPolicy\": \"Always\",\n"
                        + "        \"terminationGracePeriodSeconds\": 30,\n"
                        + "        \"dnsPolicy\": \"ClusterFirst\",\n"
                        + "        \"serviceAccountName\": \"my-release-hazelcast\",\n"
                        + "        \"serviceAccount\": \"my-release-hazelcast\",\n"
                        + "        \"nodeName\": \"gke-rafal-test-cluster-default-pool-e5fb2ea5-c7g8\",\n"
                        + "        \"securityContext\": {\n"
                        + "\n"
                        + "        },\n"
                        + "        \"hostname\": \"my-release-hazelcast-1\",\n"
                        + "        \"schedulerName\": \"default-scheduler\",\n"
                        + "        \"tolerations\": [\n"
                        + "          {\n"
                        + "            \"key\": \"node.kubernetes.io/not-ready\",\n"
                        + "            \"operator\": \"Exists\",\n"
                        + "            \"effect\": \"NoExecute\",\n"
                        + "            \"tolerationSeconds\": 300\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"key\": \"node.kubernetes.io/unreachable\",\n"
                        + "            \"operator\": \"Exists\",\n"
                        + "            \"effect\": \"NoExecute\",\n"
                        + "            \"tolerationSeconds\": 300\n"
                        + "          }\n"
                        + "        ]\n"
                        + "      },\n"
                        + "      \"status\": {\n"
                        + "        \"phase\": \"Running\",\n"
                        + "        \"conditions\": [\n"
                        + "          {\n"
                        + "            \"type\": \"Initialized\",\n"
                        + "            \"status\": \"True\",\n"
                        + "            \"lastProbeTime\": null,\n"
                        + "            \"lastTransitionTime\": \"2018-11-23T08:53:21Z\"\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"type\": \"Ready\",\n"
                        + "            \"status\": \"True\",\n"
                        + "            \"lastProbeTime\": null,\n"
                        + "            \"lastTransitionTime\": \"2018-11-23T08:53:55Z\"\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"type\": \"PodScheduled\",\n"
                        + "            \"status\": \"True\",\n"
                        + "            \"lastProbeTime\": null,\n"
                        + "            \"lastTransitionTime\": \"2018-11-23T08:53:21Z\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"hostIP\": \"10.240.0.18\",\n"
                        + "        \"podIP\": \"%s\",\n"
                        + "        \"startTime\": \"2018-11-23T08:53:21Z\",\n"
                        + "        \"containerStatuses\": [\n"
                        + "          {\n"
                        + "            \"name\": \"my-release-hazelcast\",\n"
                        + "            \"state\": {\n"
                        + "              \"running\": {\n"
                        + "                \"startedAt\": \"2018-11-23T08:53:23Z\"\n"
                        + "              }\n"
                        + "            },\n"
                        + "            \"lastState\": {\n"
                        + "\n"
                        + "            },\n"
                        + "            \"ready\": true,\n"
                        + "            \"restartCount\": 0,\n"
                        + "            \"image\": \"hazelcast/hazelcast:latest\",\n"
                        + "            \"imageID\": \"docker-pullable://hazelcast/hazelcast@sha256:a4dd478dc792ba3fa560aa41b107fed676b37c283be0306303544a0a8ebcc4c8\",\n"
                        + "            \"containerID\": \"docker://705df57d5bfb1417683800aad2b8ac38ba68abcfaf03550d797e36e79313c903\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"qosClass\": \"Burstable\"\n"
                        + "      }\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"metadata\": {\n"
                        + "        \"name\": \"%s\",\n"
                        + "        \"generateName\": \"my-release-hazelcast-mancenter-f54949c7f-\",\n"
                        + "        \"namespace\": \"default\",\n"
                        + "        \"selfLink\": \"/api/v1/namespaces/default/pods/my-release-hazelcast-mancenter-f54949c7f-k8vx8\",\n"
                        + "        \"uid\": \"21b7e41c-eefd-11e8-ab27-42010a8001ce\",\n"
                        + "        \"resourceVersion\": \"2025\",\n"
                        + "        \"creationTimestamp\": \"2018-11-23T08:52:39Z\",\n"
                        + "        \"labels\": {\n"
                        + "          \"app\": \"hazelcast\",\n"
                        + "          \"pod-template-hash\": \"910505739\",\n"
                        + "          \"release\": \"my-release\",\n"
                        + "          \"role\": \"mancenter\"\n"
                        + "        },\n"
                        + "        \"annotations\": {\n"
                        + "          \"kubernetes.io/limit-ranger\": \"LimitRanger plugin set: cpu request for container my-release-hazelcast-mancenter\"\n"
                        + "        },\n"
                        + "        \"ownerReferences\": [\n"
                        + "          {\n"
                        + "            \"apiVersion\": \"extensions/v1beta1\",\n"
                        + "            \"kind\": \"ReplicaSet\",\n"
                        + "            \"name\": \"my-release-hazelcast-mancenter-f54949c7f\",\n"
                        + "            \"uid\": \"21b450e8-eefd-11e8-ab27-42010a8001ce\",\n"
                        + "            \"controller\": true,\n"
                        + "            \"blockOwnerDeletion\": true\n"
                        + "          }\n"
                        + "        ]\n"
                        + "      },\n"
                        + "      \"spec\": {\n"
                        + "        \"volumes\": [\n"
                        + "          {\n"
                        + "            \"name\": \"mancenter-storage\",\n"
                        + "            \"persistentVolumeClaim\": {\n"
                        + "              \"claimName\": \"my-release-hazelcast-mancenter\"\n"
                        + "            }\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"name\": \"default-token-cvdgc\",\n"
                        + "            \"secret\": {\n"
                        + "              \"secretName\": \"default-token-cvdgc\",\n"
                        + "              \"defaultMode\": 420\n"
                        + "            }\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"containers\": [\n"
                        + "          {\n"
                        + "            \"name\": \"my-release-hazelcast\",\n"
                        + "            \"image\": \"hazelcast/hazelcast:latest\",\n"
                        + "            \"ports\": [\n"
                        + "              {\n"
                        + "                \"name\": \"hazelcast\",\n"
                        + "                \"containerPort\": %s,\n"
                        + "                \"protocol\": \"TCP\"\n"
                        + "              }\n"
                        + "            ],\n"
                        + "            \"resources\": {\n"
                        + "              \"requests\": {\n"
                        + "                \"cpu\": \"100m\"\n"
                        + "              }\n"
                        + "            },\n"
                        + "            \"terminationMessagePath\": \"/dev/termination-log\",\n"
                        + "            \"terminationMessagePolicy\": \"File\",\n"
                        + "            \"imagePullPolicy\": \"Always\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"restartPolicy\": \"Always\",\n"
                        + "        \"terminationGracePeriodSeconds\": 30,\n"
                        + "        \"dnsPolicy\": \"ClusterFirst\",\n"
                        + "        \"serviceAccountName\": \"default\",\n"
                        + "        \"serviceAccount\": \"default\",\n"
                        + "        \"nodeName\": \"gke-rafal-test-cluster-default-pool-e5fb2ea5-c7g8\",\n"
                        + "        \"securityContext\": {\n"
                        + "          \"runAsUser\": 0\n"
                        + "        },\n"
                        + "        \"schedulerName\": \"default-scheduler\",\n"
                        + "        \"tolerations\": [\n"
                        + "          {\n"
                        + "            \"key\": \"node.kubernetes.io/not-ready\",\n"
                        + "            \"operator\": \"Exists\",\n"
                        + "            \"effect\": \"NoExecute\",\n"
                        + "            \"tolerationSeconds\": 300\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"key\": \"node.kubernetes.io/unreachable\",\n"
                        + "            \"operator\": \"Exists\",\n"
                        + "            \"effect\": \"NoExecute\",\n"
                        + "            \"tolerationSeconds\": 300\n"
                        + "          }\n"
                        + "        ]\n"
                        + "      },\n"
                        + "      \"status\": {\n"
                        + "        \"phase\": \"Running\",\n"
                        + "        \"conditions\": [\n"
                        + "          {\n"
                        + "            \"type\": \"Initialized\",\n"
                        + "            \"status\": \"True\",\n"
                        + "            \"lastProbeTime\": null,\n"
                        + "            \"lastTransitionTime\": \"2018-11-23T08:52:46Z\"\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"type\": \"Ready\",\n"
                        + "            \"status\": \"True\",\n"
                        + "            \"lastProbeTime\": null,\n"
                        + "            \"lastTransitionTime\": \"2018-11-23T08:53:55Z\"\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"type\": \"PodScheduled\",\n"
                        + "            \"status\": \"True\",\n"
                        + "            \"lastProbeTime\": null,\n"
                        + "            \"lastTransitionTime\": \"2018-11-23T08:52:46Z\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"hostIP\": \"10.240.0.18\",\n"
                        + "        \"podIP\": \"%s\",\n"
                        + "        \"startTime\": \"2018-11-23T08:52:46Z\",\n"
                        + "        \"containerStatuses\": [\n"
                        + "          {\n"
                        + "            \"name\": \"my-release-hazelcast-mancenter\",\n"
                        + "            \"state\": {\n"
                        + "              \"running\": {\n"
                        + "                \"startedAt\": \"2018-11-23T08:53:08Z\"\n"
                        + "              }\n"
                        + "            },\n"
                        + "            \"lastState\": {\n"
                        + "\n"
                        + "            },\n"
                        + "            \"ready\": false,\n"
                        + "            \"restartCount\": 0,\n"
                        + "            \"image\": \"hazelcast/management-center:latest\",\n"
                        + "            \"imageID\": \"docker-pullable://hazelcast/management-center@sha256:0427778a84476a7b11b248b1720d22e43ee689f5148506ea532491aad1a91afa\",\n"
                        + "            \"containerID\": \"docker://99ac6e5204876a3106b01d6b3ab2466b1d63c9bd1621d86d7e984840b2c44a3f\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"qosClass\": \"Burstable\"\n"
                        + "      }\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}", POD_NAME_1, PRIVATE_PORT_1, PRIVATE_IP_1, POD_NAME_2, PRIVATE_PORT_2, PRIVATE_IP_2, NOT_READY_POD_NAME,
                NOT_READY_PRIVATE_PORT, NOT_READY_PRIVATE_IP);
    }

    private static String endpointsListBodyPublicIp() {
        return String.format("{\n"
                        + "  \"kind\": \"EndpointsList\",\n"
                        + "  \"apiVersion\": \"v1\",\n"
                        + "  \"metadata\": {\n"
                        + "    \"selfLink\": \"/api/v1/namespaces/default/endpoints\",\n"
                        + "    \"resourceVersion\": \"9096\"\n"
                        + "  },\n"
                        + "  \"items\": [\n"
                        + "    {\n"
                        + "      \"metadata\": {\n"
                        + "        \"name\": \"kubernetes\",\n"
                        + "        \"namespace\": \"default\",\n"
                        + "        \"selfLink\": \"/api/v1/namespaces/default/endpoints/kubernetes\",\n"
                        + "        \"uid\": \"ffe1ee4e-14e5-11e9-948d-42010a8001ed\",\n"
                        + "        \"resourceVersion\": \"7\",\n"
                        + "        \"creationTimestamp\": \"2019-01-10T14:42:48Z\"\n"
                        + "      },\n"
                        + "      \"subsets\": [\n"
                        + "        {\n"
                        + "          \"addresses\": [\n"
                        + "            {\n"
                        + "              \"ip\": \"35.224.1.191\"\n"
                        + "            }\n"
                        + "          ],\n"
                        + "          \"ports\": [\n"
                        + "            {\n"
                        + "              \"name\": \"https\",\n"
                        + "              \"port\": 443,\n"
                        + "              \"protocol\": \"TCP\"\n"
                        + "            }\n"
                        + "          ]\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"metadata\": {\n"
                        + "        \"name\": \"my-release-hazelcast\",\n"
                        + "        \"namespace\": \"default\",\n"
                        + "        \"selfLink\": \"/api/v1/namespaces/default/endpoints/my-release-hazelcast\",\n"
                        + "        \"uid\": \"0f387ca9-14ef-11e9-948d-42010a8001ed\",\n"
                        + "        \"resourceVersion\": \"8002\",\n"
                        + "        \"creationTimestamp\": \"2019-01-10T15:47:39Z\",\n"
                        + "        \"labels\": {\n"
                        + "          \"app\": \"hazelcast\",\n"
                        + "          \"chart\": \"hazelcast-1.1.0\",\n"
                        + "          \"heritage\": \"Tiller\",\n"
                        + "          \"release\": \"my-release\"\n"
                        + "        }\n"
                        + "      },\n"
                        + "      \"subsets\": [\n"
                        + "        {\n"
                        + "          \"addresses\": [\n"
                        + "            {\n"
                        + "              \"ip\": \"%s\",\n"
                        + "              \"nodeName\": \"%s\",\n"
                        + "              \"targetRef\": {\n"
                        + "                \"kind\": \"Pod\",\n"
                        + "                \"namespace\": \"default\",\n"
                        + "                \"name\": \"my-hazelcast-pod-0\",\n"
                        + "                \"uid\": \"0f3c26c9-14ef-11e9-948d-42010a8001ed\",\n"
                        + "                \"resourceVersion\": \"7911\"\n"
                        + "              }\n"
                        + "            },\n"
                        + "            {\n"
                        + "              \"ip\": \"%s\",\n"
                        + "              \"nodeName\": \"%s\",\n"
                        + "              \"targetRef\": {\n"
                        + "                \"kind\": \"Pod\",\n"
                        + "                \"namespace\": \"default\",\n"
                        + "                \"name\": \"my-hazelcast-pod-1\",\n"
                        + "                \"uid\": \"2908b20c-14ef-11e9-948d-42010a8001ed\",\n"
                        + "                \"resourceVersion\": \"7999\"\n"
                        + "              }\n"
                        + "            }\n"
                        + "          ],\n"
                        + "          \"ports\": [\n"
                        + "            {\n"
                        + "              \"name\": \"hzport\",\n"
                        + "              \"port\": %s,\n"
                        + "              \"protocol\": \"TCP\"\n"
                        + "            }\n"
                        + "          ]\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"metadata\": {\n"
                        + "        \"name\": \"%s\",\n"
                        + "        \"namespace\": \"default\",\n"
                        + "        \"selfLink\": \"/api/v1/namespaces/default/endpoints/my-release-hazelcast-0\",\n"
                        + "        \"uid\": \"157a83b0-14ef-11e9-948d-42010a8001ed\",\n"
                        + "        \"resourceVersion\": \"7913\",\n"
                        + "        \"creationTimestamp\": \"2019-01-10T15:47:50Z\",\n"
                        + "        \"labels\": {\n"
                        + "          \"app\": \"service-per-pod\"\n"
                        + "        }\n"
                        + "      },\n"
                        + "      \"subsets\": [\n"
                        + "        {\n"
                        + "          \"addresses\": [\n"
                        + "            {\n"
                        + "              \"ip\": \"%s\",\n"
                        + "              \"nodeName\": \"%s\",\n"
                        + "              \"targetRef\": {\n"
                        + "                \"kind\": \"Pod\",\n"
                        + "                \"namespace\": \"default\",\n"
                        + "                \"name\": \"my-hazelcast-pod-0\",\n"
                        + "                \"uid\": \"0f3c26c9-14ef-11e9-948d-42010a8001ed\",\n"
                        + "                \"resourceVersion\": \"7911\"\n"
                        + "              }\n"
                        + "            }\n"
                        + "          ],\n"
                        + "          \"ports\": [\n"
                        + "            {\n"
                        + "              \"port\": %s,\n"
                        + "              \"protocol\": \"TCP\"\n"
                        + "            }\n"
                        + "          ]\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"metadata\": {\n"
                        + "        \"name\": \"%s\",\n"
                        + "        \"namespace\": \"default\",\n"
                        + "        \"selfLink\": \"/api/v1/namespaces/default/endpoints/my-release-hazelcast-1\",\n"
                        + "        \"uid\": \"15a3ff50-14ef-11e9-948d-42010a8001ed\",\n"
                        + "        \"resourceVersion\": \"8001\",\n"
                        + "        \"creationTimestamp\": \"2019-01-10T15:47:50Z\",\n"
                        + "        \"labels\": {\n"
                        + "          \"app\": \"service-per-pod\"\n"
                        + "        }\n"
                        + "      },\n"
                        + "      \"subsets\": [\n"
                        + "        {\n"
                        + "          \"addresses\": [\n"
                        + "            {\n"
                        + "              \"ip\": \"%s\",\n"
                        + "              \"nodeName\": \"%s\",\n"
                        + "              \"targetRef\": {\n"
                        + "                \"kind\": \"Pod\",\n"
                        + "                \"namespace\": \"default\",\n"
                        + "                \"name\": \"my-hazelcast-pod-1\",\n"
                        + "                \"uid\": \"2908b20c-14ef-11e9-948d-42010a8001ed\",\n"
                        + "                \"resourceVersion\": \"7999\"\n"
                        + "              }\n"
                        + "            }\n"
                        + "          ],\n"
                        + "          \"ports\": [\n"
                        + "            {\n"
                        + "              \"port\": %s,\n"
                        + "              \"protocol\": \"TCP\"\n"
                        + "            }\n"
                        + "          ]\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"metadata\": {\n"
                        + "        \"name\": \"%s\",\n"
                        + "        \"namespace\": \"default\",\n"
                        + "        \"selfLink\": \"/api/v1/namespaces/default/endpoints/my-release-hazelcast-2\",\n"
                        + "        \"uid\": \"15a3ff50-14ef-11e9-948d-42010a8001ed\",\n"
                        + "        \"resourceVersion\": \"8001\",\n"
                        + "        \"creationTimestamp\": \"2019-01-10T15:47:50Z\",\n"
                        + "        \"labels\": {\n"
                        + "          \"app\": \"service-per-pod\"\n"
                        + "        }\n"
                        + "      },\n"
                        + "      \"subsets\": [\n"
                        + "        {\n"
                        + "          \"addresses\": [\n"
                        + "            {\n"
                        + "              \"ip\": \"%s\",\n"
                        + "              \"nodeName\": \"%s\",\n"
                        + "              \"targetRef\": {\n"
                        + "                \"kind\": \"Pod\",\n"
                        + "                \"namespace\": \"default\",\n"
                        + "                \"name\": \"my-hazelcast-pod-2\",\n"
                        + "                \"uid\": \"2908b20c-14ef-11e9-948d-42010a8001ed\",\n"
                        + "                \"resourceVersion\": \"7999\"\n"
                        + "              }\n"
                        + "            }\n"
                        + "          ],\n"
                        + "          \"ports\": [\n"
                        + "            {\n"
                        + "              \"port\": %s,\n"
                        + "              \"protocol\": \"TCP\"\n"
                        + "            }\n"
                        + "          ]\n"
                        + "        }\n"
                        + "      ]\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}", PRIVATE_IP_1, NODE_NAME_1, PRIVATE_IP_2, NODE_NAME_2, PRIVATE_PORT_1, SERVICE_NAME_1, PRIVATE_IP_1,
                NODE_NAME_1, PRIVATE_PORT_1, SERVICE_NAME_2, PRIVATE_IP_2, NODE_NAME_2, PRIVATE_PORT_2, NOT_READY_SERVICE_NAME,
                NOT_READY_PRIVATE_IP, NODE_NAME_1, NOT_READY_PRIVATE_PORT);
    }

    /**
     * TODO: Merge with nodeBody()?
     *
     * @return
     */
    private static String nodeBodyPublicIp(String nodeName, String publicIp) {
        return String.format("{\n"
                + "  \"kind\": \"Node\",\n"
                + "  \"apiVersion\": \"v1\",\n"
                + "  \"metadata\": {\n"
                + "    \"name\": \"%s\",\n"
                + "    \"selfLink\": \"/api/v1/nodes/gke-rafal-test-cluster-default-pool-7053600e-xd63\",\n"
                + "    \"uid\": \"121b756e-14e6-11e9-948d-42010a8001ed\",\n"
                + "    \"resourceVersion\": \"9347\",\n"
                + "    \"creationTimestamp\": \"2019-01-10T14:43:19Z\",\n"
                + "    \"labels\": {\n"
                + "      \"beta.kubernetes.io/arch\": \"amd64\",\n"
                + "      \"beta.kubernetes.io/fluentd-ds-ready\": \"true\",\n"
                + "      \"beta.kubernetes.io/instance-type\": \"n1-standard-1\",\n"
                + "      \"beta.kubernetes.io/os\": \"linux\",\n"
                + "      \"cloud.google.com/gke-nodepool\": \"default-pool\",\n"
                + "      \"cloud.google.com/gke-os-distribution\": \"cos\",\n"
                + "      \"failure-domain.beta.kubernetes.io/region\": \"us-central1\",\n"
                + "      \"failure-domain.beta.kubernetes.io/zone\": \"us-central1-a\",\n"
                + "      \"kubernetes.io/hostname\": \"gke-rafal-test-cluster-default-pool-7053600e-xd63\"\n"
                + "    },\n"
                + "    \"annotations\": {\n"
                + "      \"node.alpha.kubernetes.io/ttl\": \"0\",\n"
                + "      \"volumes.kubernetes.io/controller-managed-attach-detach\": \"true\"\n"
                + "    }\n"
                + "  },\n"
                + "  \"spec\": {\n"
                + "    \"podCIDR\": \"10.16.0.0/24\",\n"
                + "    \"externalID\": \"5236188219805722435\",\n"
                + "    \"providerID\": \"gce://hazelcast-33/us-central1-a/gke-rafal-test-cluster-default-pool-7053600e-xd63\"\n"
                + "  },\n"
                + "  \"status\": {\n"
                + "    \"capacity\": {\n"
                + "      \"cpu\": \"1\",\n"
                + "      \"ephemeral-storage\": \"98868448Ki\",\n"
                + "      \"hugepages-2Mi\": \"0\",\n"
                + "      \"memory\": \"3787604Ki\",\n"
                + "      \"pods\": \"110\"\n"
                + "    },\n"
                + "    \"allocatable\": {\n"
                + "      \"cpu\": \"940m\",\n"
                + "      \"ephemeral-storage\": \"47093746742\",\n"
                + "      \"hugepages-2Mi\": \"0\",\n"
                + "      \"memory\": \"2702164Ki\",\n"
                + "      \"pods\": \"110\"\n"
                + "    },\n"
                + "    \"conditions\": [\n"
                + "      {\n"
                + "        \"type\": \"FrequentUnregisterNetDevice\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:13Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:47:34Z\",\n"
                + "        \"reason\": \"UnregisterNetDevice\",\n"
                + "        \"message\": \"node is functioning properly\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"KernelDeadlock\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:13Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:42:33Z\",\n"
                + "        \"reason\": \"KernelHasNoDeadlock\",\n"
                + "        \"message\": \"kernel has no deadlock\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"NetworkUnavailable\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T14:43:41Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:41Z\",\n"
                + "        \"reason\": \"RouteCreated\",\n"
                + "        \"message\": \"RouteController created a route\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"OutOfDisk\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:10Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:19Z\",\n"
                + "        \"reason\": \"KubeletHasSufficientDisk\",\n"
                + "        \"message\": \"kubelet has sufficient disk space available\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"MemoryPressure\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:10Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:19Z\",\n"
                + "        \"reason\": \"KubeletHasSufficientMemory\",\n"
                + "        \"message\": \"kubelet has sufficient memory available\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"DiskPressure\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:10Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:19Z\",\n"
                + "        \"reason\": \"KubeletHasNoDiskPressure\",\n"
                + "        \"message\": \"kubelet has no disk pressure\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"PIDPressure\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:10Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:19Z\",\n"
                + "        \"reason\": \"KubeletHasSufficientPID\",\n"
                + "        \"message\": \"kubelet has sufficient PID available\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"Ready\",\n"
                + "        \"status\": \"True\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:10Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:39Z\",\n"
                + "        \"reason\": \"KubeletReady\",\n"
                + "        \"message\": \"kubelet is posting ready status. AppArmor enabled\"\n"
                + "      }\n"
                + "    ],\n"
                + "    \"addresses\": [\n"
                + "      {\n"
                + "        \"type\": \"InternalIP\",\n"
                + "        \"address\": \"10.240.0.21\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"ExternalIP\",\n"
                + "        \"address\": \"%s\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"Hostname\",\n"
                + "        \"address\": \"gke-rafal-test-cluster-default-pool-7053600e-xd63\"\n"
                + "      }\n"
                + "    ],\n"
                + "    \"daemonEndpoints\": {\n"
                + "      \"kubeletEndpoint\": {\n"
                + "        \"Port\": 10250\n"
                + "      }\n"
                + "    },\n"
                + "    \"nodeInfo\": {\n"
                + "      \"machineID\": \"71d2c8b7948ac46a4df79018bc4d74f7\",\n"
                + "      \"systemUUID\": \"71D2C8B7-948A-C46A-4DF7-9018BC4D74F7\",\n"
                + "      \"bootID\": \"eea969d4-a4b7-4b6c-abb7-16618a0518a7\",\n"
                + "      \"kernelVersion\": \"4.14.65+\",\n"
                + "      \"osImage\": \"Container-Optimized OS from Google\",\n"
                + "      \"containerRuntimeVersion\": \"docker://17.3.2\",\n"
                + "      \"kubeletVersion\": \"v1.10.9-gke.5\",\n"
                + "      \"kubeProxyVersion\": \"v1.10.9-gke.5\",\n"
                + "      \"operatingSystem\": \"linux\",\n"
                + "      \"architecture\": \"amd64\"\n"
                + "    },\n"
                + "    \"images\": [\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"gcr.io/stackdriver-agents/stackdriver-logging-agent@sha256:a33f69d0034fdce835a1eb7df8a051ea74323f3fc30d911bbd2e3f2aef09fc93\",\n"
                + "          \"gcr.io/stackdriver-agents/stackdriver-logging-agent:0.3-1.5.34-1-k8s-1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 554981103\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/node-problem-detector@sha256:f95cab985c26b2f46e9bd43283e0bfa88860c14e0fb0649266babe8b65e9eb2b\",\n"
                + "          \"k8s.gcr.io/node-problem-detector:v0.4.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 286572743\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/fluentd-elasticsearch@sha256:b8c94527b489fb61d3d81ce5ad7f3ddbb7be71e9620a3a36e2bede2f2e487d73\",\n"
                + "          \"k8s.gcr.io/fluentd-elasticsearch:v2.0.4\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 135716379\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"hazelcast/hazelcast@sha256:4ab4f2156111e2210f1ead39afe81a37c16f428f57e3c6bdd6645cf65dc7a194\",\n"
                + "          \"hazelcast/hazelcast:3.11\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 123250375\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/fluentd-gcp-scaler@sha256:457a13df66534b94bab627c4c2dc2df0ee5153a5d0f0afd27502bd46bd8da81d\",\n"
                + "          \"k8s.gcr.io/fluentd-gcp-scaler:0.5\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 103488147\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"gcr.io/google_containers/kube-proxy:v1.10.9-gke.5\",\n"
                + "          \"k8s.gcr.io/kube-proxy:v1.10.9-gke.5\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 103149276\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/kubernetes-dashboard-amd64@sha256:dc4026c1b595435ef5527ca598e1e9c4343076926d7d62b365c44831395adbd0\",\n"
                + "          \"k8s.gcr.io/kubernetes-dashboard-amd64:v1.8.3\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 102319441\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/event-exporter@sha256:7f9cd7cb04d6959b0aa960727d04fa86759008048c785397b7b0d9dff0007516\",\n"
                + "          \"k8s.gcr.io/event-exporter:v0.2.3\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 94171943\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/prometheus-to-sd@sha256:6c0c742475363d537ff059136e5d5e4ab1f512ee0fd9b7ca42ea48bc309d1662\",\n"
                + "          \"k8s.gcr.io/prometheus-to-sd:v0.3.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 88077694\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/kube-addon-manager@sha256:3519273916ba45cfc9b318448d4629819cb5fbccbb0822cce054dd8c1f68cb60\",\n"
                + "          \"k8s.gcr.io/kube-addon-manager:v8.6\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 78384272\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/heapster-amd64@sha256:fc33c690a3a446de5abc24b048b88050810a58b9e4477fa763a43d7df029301a\",\n"
                + "          \"k8s.gcr.io/heapster-amd64:v1.5.3\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 75318342\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/rescheduler@sha256:66a900b01c70d695e112d8fa7779255640aab77ccc31f2bb661e6c674fe0d162\",\n"
                + "          \"k8s.gcr.io/rescheduler:v0.3.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 74659350\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/ingress-gce-glbc-amd64@sha256:31d36bbd9c44caffa135fc78cf0737266fcf25e3cf0cd1c2fcbfbc4f7309cc52\",\n"
                + "          \"k8s.gcr.io/ingress-gce-glbc-amd64:v1.1.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 67801919\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"gcr.io/google-containers/prometheus-to-sd@sha256:be220ec4a66275442f11d420033c106bb3502a3217a99c806eef3cf9858788a2\",\n"
                + "          \"gcr.io/google-containers/prometheus-to-sd:v0.2.3\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 55342106\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/cpvpa-amd64@sha256:cfe7b0a11c9c8e18c87b1eb34fef9a7cbb8480a8da11fc2657f78dbf4739f869\",\n"
                + "          \"k8s.gcr.io/cpvpa-amd64:v0.6.0\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 51785854\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/cluster-proportional-autoscaler-amd64@sha256:003f98d9f411ddfa6ff6d539196355e03ddd69fa4ed38c7ffb8fec6f729afe2d\",\n"
                + "          \"k8s.gcr.io/cluster-proportional-autoscaler-amd64:1.1.2-r2\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 49648481\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/ip-masq-agent-amd64@sha256:1ffda57d87901bc01324c82ceb2145fe6a0448d3f0dd9cb65aa76a867cd62103\",\n"
                + "          \"k8s.gcr.io/ip-masq-agent-amd64:v2.1.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 49612505\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/k8s-dns-kube-dns-amd64@sha256:b99fc3eee2a9f052f7eb4cc00f15eb12fc405fa41019baa2d6b79847ae7284a8\",\n"
                + "          \"k8s.gcr.io/k8s-dns-kube-dns-amd64:1.14.10\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 49549457\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/metrics-server-amd64@sha256:49a9f12f7067d11f42c803dbe61ed2c1299959ad85cb315b25ff7eef8e6b8892\",\n"
                + "          \"k8s.gcr.io/metrics-server-amd64:v0.2.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 42541759\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/k8s-dns-sidecar-amd64@sha256:4f1ab957f87b94a5ec1edc26fae50da2175461f00afecf68940c4aa079bd08a4\",\n"
                + "          \"k8s.gcr.io/k8s-dns-sidecar-amd64:1.14.10\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 41635309\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/k8s-dns-dnsmasq-nanny-amd64@sha256:bbb2a290a568125b3b996028958eb773f33b5b87a6b37bf38a28f8b62dddb3c8\",\n"
                + "          \"k8s.gcr.io/k8s-dns-dnsmasq-nanny-amd64:1.14.10\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 40372149\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/addon-resizer@sha256:507aa9845ecce1fdde4d61f530c802f4dc2974c700ce0db7730866e442db958d\",\n"
                + "          \"k8s.gcr.io/addon-resizer:1.8.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 32968591\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/metadata-proxy@sha256:5be758058e67b578f7041498e2daca46ccd7426bc602d35ed0f50bd4a3659d50\",\n"
                + "          \"k8s.gcr.io/metadata-proxy:v0.1.10\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 8953717\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/defaultbackend@sha256:865b0c35e6da393b8e80b7e3799f777572399a4cff047eb02a81fa6e7a48ed4b\",\n"
                + "          \"k8s.gcr.io/defaultbackend:1.4\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 4844064\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/busybox@sha256:545e6a6310a27636260920bc07b994a299b6708a1b26910cfefd335fdfb60d2b\",\n"
                + "          \"k8s.gcr.io/busybox:1.27\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 1129289\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/busybox@sha256:4bdd623e848417d96127e16037743f0cd8b528c026e9175e22a84f639eca58ff\",\n"
                + "          \"k8s.gcr.io/busybox:1.24\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 1113554\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"asia.gcr.io/google-containers/pause-amd64@sha256:163ac025575b775d1c0f9bf0bdd0f086883171eb475b5068e7defa4ca9e76516\",\n"
                + "          \"eu.gcr.io/google-containers/pause-amd64@sha256:163ac025575b775d1c0f9bf0bdd0f086883171eb475b5068e7defa4ca9e76516\",\n"
                + "          \"gcr.io/google-containers/pause-amd64@sha256:163ac025575b775d1c0f9bf0bdd0f086883171eb475b5068e7defa4ca9e76516\",\n"
                + "          \"asia.gcr.io/google-containers/pause-amd64:3.0\",\n"
                + "          \"eu.gcr.io/google-containers/pause-amd64:3.0\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 746888\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/pause-amd64@sha256:59eec8837a4d942cc19a52b8c09ea75121acc38114a2c68b98983ce9356b8610\",\n"
                + "          \"k8s.gcr.io/pause-amd64:3.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 742472\n"
                + "      }\n"
                + "    ]\n"
                + "  }\n"
                + "}", nodeName, publicIp);
    }

    private static String serviceBodyLoadBalancerPublicIp(String serviceName, String loadBalancerPublicIp, String servicePort) {

        return String.format("{\n"
                + "  \"kind\": \"Service\",\n"
                + "  \"apiVersion\": \"v1\",\n"
                + "  \"metadata\": {\n"
                + "    \"name\": \"%s\",\n"
                + "    \"namespace\": \"default\",\n"
                + "    \"selfLink\": \"/api/v1/namespaces/default/services/my-release-hazelcast-1\",\n"
                + "    \"uid\": \"50cf3f1f-3430-11e9-84a1-42010a80000a\",\n"
                + "    \"resourceVersion\": \"6175\",\n"
                + "    \"creationTimestamp\": \"2019-02-19T10:22:53Z\",\n"
                + "    \"labels\": {\n"
                + "      \"app\": \"service-per-pod\"\n"
                + "    },\n"
                + "    \"annotations\": {\n"
                + "      \"metacontroller.k8s.io/decorator-controller\": \"service-per-pod\",\n"
                + "      \"metacontroller.k8s.io/last-applied-configuration\": \"{\\\"apiVersion\\\":\\\"v1\\\",\\\"kind\\\":\\\"Service\\\",\\\"metadata\\\":{\\\"annotations\\\":{\\\"metacontroller.k8s.io/decorator-controller\\\":\\\"service-per-pod\\\"},\\\"labels\\\":{\\\"app\\\":\\\"service-per-pod\\\"},\\\"name\\\":\\\"my-release-hazelcast-1\\\"},\\\"spec\\\":{\\\"ports\\\":[{\\\"port\\\":5701,\\\"targetPort\\\":5701}],\\\"selector\\\":{\\\"statefulset.kubernetes.io/pod-name\\\":\\\"my-release-hazelcast-1\\\"},\\\"type\\\":\\\"LoadBalancer\\\"}}\"\n"
                + "    },\n"
                + "    \"ownerReferences\": [\n"
                + "      {\n"
                + "        \"apiVersion\": \"apps/v1beta1\",\n"
                + "        \"kind\": \"StatefulSet\",\n"
                + "        \"name\": \"my-release-hazelcast\",\n"
                + "        \"uid\": \"4ad0ee47-3430-11e9-84a1-42010a80000a\",\n"
                + "        \"controller\": true,\n"
                + "        \"blockOwnerDeletion\": true\n"
                + "      }\n"
                + "    ]\n"
                + "  },\n"
                + "  \"spec\": {\n"
                + "    \"ports\": [\n"
                + "      {\n"
                + "        \"protocol\": \"TCP\",\n"
                + "        \"port\": %s,\n"
                + "        \"targetPort\": 5701,\n"
                + "        \"nodePort\": 31916\n"
                + "      }\n"
                + "    ],\n"
                + "    \"selector\": {\n"
                + "      \"statefulset.kubernetes.io/pod-name\": \"my-release-hazelcast-1\"\n"
                + "    },\n"
                + "    \"clusterIP\": \"10.19.240.108\",\n"
                + "    \"type\": \"LoadBalancer\",\n"
                + "    \"sessionAffinity\": \"None\",\n"
                + "    \"externalTrafficPolicy\": \"Cluster\"\n"
                + "  },\n"
                + "  \"status\": {\n"
                + "    \"loadBalancer\": {\n"
                + "      \"ingress\": [\n"
                + "        {\n"
                + "          \"ip\": \"%s\"\n"
                + "        }\n"
                + "      ]\n"
                + "    }\n"
                + "  }\n"
                + "}", serviceName, servicePort, loadBalancerPublicIp);
    }

    private static String serviceBodyNodePublicIp(String serviceName, String nodePort) {
        return String.format("{\n"
                + "  \"kind\": \"Service\",\n"
                + "  \"apiVersion\": \"v1\",\n"
                + "  \"metadata\": {\n"
                + "    \"name\": \"%s\",\n"
                + "    \"namespace\": \"default\",\n"
                + "    \"selfLink\": \"/api/v1/namespaces/default/services/my-release-hazelcast-0\",\n"
                + "    \"uid\": \"156c7412-14ef-11e9-948d-42010a8001ed\",\n"
                + "    \"resourceVersion\": \"7844\",\n"
                + "    \"creationTimestamp\": \"2019-01-10T15:47:50Z\",\n"
                + "    \"labels\": {\n"
                + "      \"app\": \"service-per-pod\"\n"
                + "    },\n"
                + "    \"annotations\": {\n"
                + "      \"metacontroller.k8s.io/decorator-controller\": \"service-per-pod\",\n"
                + "      \"metacontroller.k8s.io/last-applied-configuration\": \"{\\\"apiVersion\\\":\\\"v1\\\",\\\"kind\\\":\\\"Service\\\",\\\"metadata\\\":{\\\"annotations\\\":{\\\"metacontroller.k8s.io/decorator-controller\\\":\\\"service-per-pod\\\"},\\\"labels\\\":{\\\"app\\\":\\\"service-per-pod\\\"},\\\"name\\\":\\\"my-release-hazelcast-0\\\"},\\\"spec\\\":{\\\"ports\\\":[{\\\"port\\\":5701,\\\"targetPort\\\":5701}],\\\"selector\\\":{\\\"statefulset.kubernetes.io/pod-name\\\":\\\"my-release-hazelcast-0\\\"},\\\"type\\\":\\\"NodePort\\\"}}\"\n"
                + "    },\n"
                + "    \"ownerReferences\": [\n"
                + "      {\n"
                + "        \"apiVersion\": \"apps/v1beta1\",\n"
                + "        \"kind\": \"StatefulSet\",\n"
                + "        \"name\": \"my-release-hazelcast\",\n"
                + "        \"uid\": \"0f383f08-14ef-11e9-948d-42010a8001ed\",\n"
                + "        \"controller\": true,\n"
                + "        \"blockOwnerDeletion\": true\n"
                + "      }\n"
                + "    ]\n"
                + "  },\n"
                + "  \"spec\": {\n"
                + "    \"ports\": [\n"
                + "      {\n"
                + "        \"protocol\": \"TCP\",\n"
                + "        \"port\": 5701,\n"
                + "        \"targetPort\": 5701,\n"
                + "        \"nodePort\": %s\n"
                + "      }\n"
                + "    ],\n"
                + "    \"selector\": {\n"
                + "      \"statefulset.kubernetes.io/pod-name\": \"my-release-hazelcast-0\"\n"
                + "    },\n"
                + "    \"clusterIP\": \"10.19.242.81\",\n"
                + "    \"type\": \"NodePort\",\n"
                + "    \"sessionAffinity\": \"None\",\n"
                + "    \"externalTrafficPolicy\": \"Cluster\"\n"
                + "  },\n"
                + "  \"status\": {\n"
                + "    \"loadBalancer\": {\n"
                + "\n"
                + "    }\n"
                + "  }\n"
                + "}", serviceName, nodePort);
    }

    /**
     * Real response recorded from the Kubernetes API call "/api/v1/namespaces/{namespace}/endpoints".
     */
    private static String endpointsListBody() {
        return String.format(
                "{" +
                        "  \"kind\": \"EndpointsList\"," +
                        "  \"apiVersion\": \"v1\"," +
                        "  \"metadata\": {" +
                        "    \"selfLink\": \"/api/v1/namespaces/default/endpoints\"," +
                        "    \"resourceVersion\": \"8792\"" +
                        "  }," +
                        "  \"items\": [" +
                        "    {" +
                        "      \"metadata\": {" +
                        "        \"name\": \"kubernetes\"," +
                        "        \"namespace\": \"default\"," +
                        "        \"selfLink\": \"/api/v1/namespaces/default/endpoints/kubernetes\"," +
                        "        \"uid\": \"01c5aaa4-8411-11e8-abd2-00155d395157\"," +
                        "        \"resourceVersion\": \"38\"," +
                        "        \"creationTimestamp\": \"2018-07-10T07:15:21Z\"" +
                        "      }," +
                        "      \"subsets\": [" +
                        "        {" +
                        "          \"addresses\": [" +
                        "            {" +
                        "              \"ip\": \"%s\"," +
                        "              \"hazelcast-service-port\" :%s" +
                        "            }" +
                        "          ]," +
                        "          \"ports\": [" +
                        "            {" +
                        "              \"name\": \"https\"," +
                        "              \"port\": 8443," +
                        "              \"protocol\": \"TCP\"" +
                        "            }" +
                        "          ]" +
                        "        }" +
                        "      ]" +
                        "    }," +
                        "    {" +
                        "      \"metadata\": {" +
                        "        \"name\": \"my-hazelcast\"," +
                        "        \"namespace\": \"default\"," +
                        "        \"selfLink\": \"/api/v1/namespaces/default/endpoints/my-hazelcast\"," +
                        "        \"uid\": \"80f7f03d-8425-11e8-abd2-00155d395157\"," +
                        "        \"resourceVersion\": \"8788\"," +
                        "        \"creationTimestamp\": \"2018-07-10T09:42:04Z\"," +
                        "        \"labels\": {" +
                        "          \"app\": \"hazelcast\"," +
                        "          \"chart\": \"hazelcast-1.0.0\"," +
                        "          \"heritage\": \"Tiller\"," +
                        "          \"release\": \"my-hazelcast\"" +
                        "        }" +
                        "      }," +
                        "      \"subsets\": [" +
                        "        {" +
                        "          \"addresses\": [" +
                        "            {" +
                        "              \"ip\": \"%s\"," +
                        "              \"nodeName\": \"minikube\"," +
                        "              \"hazelcast-service-port\" : %s," +
                        "              \"targetRef\": {" +
                        "                \"kind\": \"Pod\"," +
                        "                \"namespace\": \"default\"," +
                        "                \"name\": \"my-hazelcast-0\"," +
                        "                \"uid\": \"80f20bcb-8425-11e8-abd2-00155d395157\"," +
                        "                \"resourceVersion\": \"8771\"" +
                        "              }" +
                        "            }" +
                        "          ]," +
                        "          \"notReadyAddresses\": [" +
                        "            {" +
                        "              \"ip\": \"%s\"," +
                        "              \"nodeName\": \"minikube\"," +
                        "              \"targetRef\": {" +
                        "                \"kind\": \"Pod\"," +
                        "                \"namespace\": \"default\"," +
                        "                \"name\": \"my-hazelcast-1\"," +
                        "                \"uid\": \"95bd3636-8425-11e8-abd2-00155d395157\"," +
                        "                \"resourceVersion\": \"8787\"" +
                        "              }" +
                        "            }" +
                        "          ]," +
                        "          \"ports\": [" +
                        "            {" +
                        "              \"name\": \"hzport\"," +
                        "              \"port\": 5701," +
                        "              \"protocol\": \"TCP\"" +
                        "            }" +
                        "          ]" +
                        "        }" +
                        "      ]" +
                        "    }" +
                        "  ]" +
                        "}"
                , PRIVATE_IP_1, PRIVATE_PORT_1, PRIVATE_IP_2, PRIVATE_PORT_2, NOT_READY_PRIVATE_IP);
    }

    /**
     * Real response recorded from the Kubernetes API call "/api/v1/namespaces/{namespace}/endpoints/{endpoint}".
     */
    private static String endpointsBody() {
        return String.format(
                "{" +
                        "  \"kind\": \"Endpoints\"," +
                        "  \"apiVersion\": \"v1\"," +
                        "  \"metadata\": {" +
                        "    \"name\": \"my-hazelcast\"," +
                        "    \"namespace\": \"default\"," +
                        "    \"selfLink\": \"/api/v1/namespaces/default/endpoints/my-hazelcast\"," +
                        "    \"uid\": \"deefb354-8443-11e8-abd2-00155d395157\"," +
                        "    \"resourceVersion\": \"18816\"," +
                        "    \"creationTimestamp\": \"2018-07-10T13:19:27Z\"," +
                        "    \"labels\": {" +
                        "      \"app\": \"hazelcast\"," +
                        "      \"chart\": \"hazelcast-1.0.0\"," +
                        "      \"heritage\": \"Tiller\"," +
                        "      \"release\": \"my-hazelcast\"" +
                        "    }" +
                        "  }," +
                        "  \"subsets\": [" +
                        "    {" +
                        "      \"addresses\": [" +
                        "        {" +
                        "          \"ip\": \"%s\"," +
                        "          \"nodeName\": \"minikube\"," +
                        "          \"hazelcast-service-port\" : %s," +
                        "          \"targetRef\": {" +
                        "            \"kind\": \"Pod\"," +
                        "            \"namespace\": \"default\"," +
                        "            \"name\": \"my-hazelcast-0\"," +
                        "            \"uid\": \"def2f426-8443-11e8-abd2-00155d395157\"," +
                        "            \"resourceVersion\": \"18757\"" +
                        "          }" +
                        "        }," +
                        "        {" +
                        "          \"ip\": \"%s\"," +
                        "          \"nodeName\": \"minikube\"," +
                        "          \"hazelcast-service-port\" : %s," +
                        "          \"targetRef\": {" +
                        "            \"kind\": \"Pod\"," +
                        "            \"namespace\": \"default\"," +
                        "            \"name\": \"my-hazelcast-1\"," +
                        "            \"uid\": \"f3b96106-8443-11e8-abd2-00155d395157\"," +
                        "            \"resourceVersion\": \"18815\"" +
                        "          }" +
                        "        }" +
                        "      ]," +
                        "      \"ports\": [" +
                        "        {" +
                        "          \"name\": \"hzport\"," +
                        "          \"port\": 5701," +
                        "          \"protocol\": \"TCP\"" +
                        "        }" +
                        "      ]" +
                        "    }" +
                        "  ]" +
                        "}"
                , PRIVATE_IP_1, PRIVATE_PORT_1, PRIVATE_IP_2, PRIVATE_PORT_2);
    }

    /**
     * Real response recorded from the Kubernetes API call "/api/v1/namespaces/{namespace}/pods/{pod-name}".
     */
    private static String podBody(String nodeName) {
        return String.format(
                "{  \"kind\": \"Pod\",\n"
                        + "  \"apiVersion\": \"v1\",\n"
                        + "  \"metadata\": {\n"
                        + "    \"name\": \"my-release-hazelcast-0\",\n"
                        + "    \"generateName\": \"my-release-hazelcast-\",\n"
                        + "    \"namespace\": \"default\",\n"
                        + "    \"selfLink\": \"/api/v1/namespaces/default/pods/my-release-hazelcast-0\",\n"
                        + "    \"uid\": \"53112ead-0511-11e9-9c53-42010a800013\",\n"
                        + "    \"resourceVersion\": \"7724\",\n"
                        + "    \"creationTimestamp\": \"2018-12-21T11:12:37Z\",\n"
                        + "    \"labels\": {\n"
                        + "      \"app\": \"hazelcast\",\n"
                        + "      \"controller-revision-hash\": \"my-release-hazelcast-695d9d97dd\",\n"
                        + "      \"release\": \"my-release\",\n"
                        + "      \"role\": \"hazelcast\",\n"
                        + "      \"statefulset.kubernetes.io/pod-name\": \"my-release-hazelcast-0\"\n"
                        + "    },\n"
                        + "    \"annotations\": {\n"
                        + "      \"kubernetes.io/limit-ranger\": \"LimitRanger plugin set: cpu request for container my-release-hazelcast\"\n"
                        + "    },\n"
                        + "    \"ownerReferences\": [\n"
                        + "      {\n"
                        + "        \"apiVersion\": \"apps/v1\",\n"
                        + "        \"kind\": \"StatefulSet\",\n"
                        + "        \"name\": \"my-release-hazelcast\",\n"
                        + "        \"uid\": \"53096f3b-0511-11e9-9c53-42010a800013\",\n"
                        + "        \"controller\": true,\n"
                        + "        \"blockOwnerDeletion\": true\n"
                        + "      }\n"
                        + "    ]\n"
                        + "  },\n"
                        + "  \"spec\": {\n"
                        + "    \"volumes\": [\n"
                        + "      {\n"
                        + "        \"name\": \"hazelcast-storage\",\n"
                        + "        \"configMap\": {\n"
                        + "          \"name\": \"my-release-hazelcast-configuration\",\n"
                        + "          \"defaultMode\": 420\n"
                        + "        }\n"
                        + "      },\n"
                        + "      {\n"
                        + "        \"name\": \"my-release-hazelcast-token-j89v4\",\n"
                        + "        \"secret\": {\n"
                        + "          \"secretName\": \"my-release-hazelcast-token-j89v4\",\n"
                        + "          \"defaultMode\": 420\n"
                        + "        }\n"
                        + "      }\n"
                        + "    ],\n"
                        + "    \"containers\": [\n"
                        + "      {\n"
                        + "        \"name\": \"my-release-hazelcast\",\n"
                        + "        \"image\": \"hazelcast/hazelcast:latest\",\n"
                        + "        \"ports\": [\n"
                        + "          {\n"
                        + "            \"name\": \"hazelcast\",\n"
                        + "            \"containerPort\": 5701,\n"
                        + "            \"protocol\": \"TCP\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"env\": [\n"
                        + "          {\n"
                        + "            \"name\": \"JAVA_OPTS\",\n"
                        + "            \"value\": \"-Dhazelcast.rest.enabled=true -Dhazelcast.config=/data/hazelcast/hazelcast.xml -DserviceName=my-release-hazelcast -Dnamespace=default -Dhazelcast.mancenter.enabled=true -Dhazelcast.mancenter.url=http://my-release-hazelcast-mancenter:8080/hazelcast-mancenter -Dhazelcast.shutdownhook.policy=GRACEFUL -Dhazelcast.shutdownhook.enabled=true -Dhazelcast.graceful.shutdown.max.wait=600 \"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"resources\": {\n"
                        + "          \"requests\": {\n"
                        + "            \"cpu\": \"100m\"\n"
                        + "          }\n"
                        + "        },\n"
                        + "        \"volumeMounts\": [\n"
                        + "          {\n"
                        + "            \"name\": \"hazelcast-storage\",\n"
                        + "            \"mountPath\": \"/data/hazelcast\"\n"
                        + "          },\n"
                        + "          {\n"
                        + "            \"name\": \"my-release-hazelcast-token-j89v4\",\n"
                        + "            \"readOnly\": true,\n"
                        + "            \"mountPath\": \"/var/run/secrets/kubernetes.io/serviceaccount\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"terminationMessagePath\": \"/dev/termination-log\",\n"
                        + "        \"terminationMessagePolicy\": \"File\",\n"
                        + "        \"imagePullPolicy\": \"Always\"\n"
                        + "      }\n"
                        + "    ],\n"
                        + "    \"restartPolicy\": \"Always\",\n"
                        + "    \"terminationGracePeriodSeconds\": 600,\n"
                        + "    \"dnsPolicy\": \"ClusterFirst\",\n"
                        + "    \"serviceAccountName\": \"my-release-hazelcast\",\n"
                        + "    \"serviceAccount\": \"my-release-hazelcast\",\n"
                        + "    \"nodeName\": \"%s\",\n"
                        + "    \"securityContext\": {\n"
                        + "      \"runAsUser\": 1001,\n"
                        + "      \"fsGroup\": 1001\n"
                        + "    },\n"
                        + "    \"hostname\": \"my-release-hazelcast-0\",\n"
                        + "    \"schedulerName\": \"default-scheduler\",\n"
                        + "    \"tolerations\": [\n"
                        + "      {\n"
                        + "        \"key\": \"node.kubernetes.io/not-ready\",\n"
                        + "        \"operator\": \"Exists\",\n"
                        + "        \"effect\": \"NoExecute\",\n"
                        + "        \"tolerationSeconds\": 300\n"
                        + "      },\n"
                        + "      {\n"
                        + "        \"key\": \"node.kubernetes.io/unreachable\",\n"
                        + "        \"operator\": \"Exists\",\n"
                        + "        \"effect\": \"NoExecute\",\n"
                        + "        \"tolerationSeconds\": 300\n"
                        + "      }\n"
                        + "    ]\n"
                        + "  },\n"
                        + "  \"status\": {\n"
                        + "  }\n"
                        + "}", nodeName);
    }

    /**
     * Real response recorded from the Kubernetes (version < 1.13) API call "/api/v1/nodes/{node-name}".
     */
    private static String nodeBetaBody() {
        return String.format(
                "{"
                        + "  \"kind\": \"Node\",\n"
                        + "  \"apiVersion\": \"v1\",\n"
                        + "  \"metadata\": {\n"
                        + "    \"name\": \"gke-rafal-test-cluster-default-pool-9238654c-12tz\",\n"
                        + "    \"selfLink\": \"/api/v1/nodes/gke-rafal-test-cluster-default-pool-9238654c-12tz\",\n"
                        + "    \"uid\": \"ceab9c17-0508-11e9-9c53-42010a800013\",\n"
                        + "    \"resourceVersion\": \"7954\",\n"
                        + "    \"creationTimestamp\": \"2018-12-21T10:11:39Z\",\n"
                        + "    \"labels\": {\n"
                        + "      \"beta.kubernetes.io/arch\": \"amd64\",\n"
                        + "      \"beta.kubernetes.io/fluentd-ds-ready\": \"true\",\n"
                        + "      \"beta.kubernetes.io/instance-type\": \"n1-standard-1\",\n"
                        + "      \"beta.kubernetes.io/os\": \"linux\",\n"
                        + "      \"cloud.google.com/gke-nodepool\": \"default-pool\",\n"
                        + "      \"cloud.google.com/gke-os-distribution\": \"cos\",\n"
                        + "      \"failure-domain.beta.kubernetes.io/region\": \"us-central1\",\n"
                        + "      \"failure-domain.beta.kubernetes.io/zone\": \"%s\",\n"
                        + "      \"kubernetes.io/hostname\": \"gke-rafal-test-cluster-default-pool-9238654c-12tz\"\n"
                        + "    },\n"
                        + "    \"annotations\": {\n"
                        + "      \"node.alpha.kubernetes.io/ttl\": \"0\",\n"
                        + "      \"volumes.kubernetes.io/controller-managed-attach-detach\": \"true\"\n"
                        + "    }\n"
                        + "  },\n"
                        + "  \"spec\": {\n"
                        + "  },\n"
                        + "  \"status\": {\n"
                        + "  }\n"
                        + "}", ZONE);
    }

    /**
     * Real response recorded from the Kubernetes (version >= 1.13) API call "/api/v1/nodes/{node-name}".
     */
    private static String nodeBody() {
        return String.format(
                "{"
                        + "  \"kind\": \"Node\",\n"
                        + "  \"apiVersion\": \"v1\",\n"
                        + "  \"metadata\": {\n"
                        + "    \"name\": \"gke-rafal-test-cluster-default-pool-9238654c-12tz\",\n"
                        + "    \"selfLink\": \"/api/v1/nodes/gke-rafal-test-cluster-default-pool-9238654c-12tz\",\n"
                        + "    \"uid\": \"ceab9c17-0508-11e9-9c53-42010a800013\",\n"
                        + "    \"resourceVersion\": \"7954\",\n"
                        + "    \"creationTimestamp\": \"2018-12-21T10:11:39Z\",\n"
                        + "    \"labels\": {\n"
                        + "      \"beta.kubernetes.io/arch\": \"amd64\",\n"
                        + "      \"beta.kubernetes.io/fluentd-ds-ready\": \"true\",\n"
                        + "      \"beta.kubernetes.io/instance-type\": \"n1-standard-1\",\n"
                        + "      \"beta.kubernetes.io/os\": \"linux\",\n"
                        + "      \"cloud.google.com/gke-nodepool\": \"default-pool\",\n"
                        + "      \"cloud.google.com/gke-os-distribution\": \"cos\",\n"
                        + "      \"failure-domain.beta.kubernetes.io/region\": \"deprecated-region\",\n"
                        + "      \"failure-domain.beta.kubernetes.io/zone\": \"deprecated-zone\",\n"
                        + "      \"failure-domain.kubernetes.io/region\": \"us-central1\",\n"
                        + "      \"failure-domain.kubernetes.io/zone\": \"%s\",\n"
                        + "      \"kubernetes.io/hostname\": \"gke-rafal-test-cluster-default-pool-9238654c-12tz\"\n"
                        + "    },\n"
                        + "    \"annotations\": {\n"
                        + "      \"node.alpha.kubernetes.io/ttl\": \"0\",\n"
                        + "      \"volumes.kubernetes.io/controller-managed-attach-detach\": \"true\"\n"
                        + "    }\n"
                        + "  },\n"
                        + "  \"spec\": {\n"
                        + "  },\n"
                        + "  \"status\": {\n"
                        + "  }\n"
                        + "}", ZONE);
    }

    /**
     * TODO: Merge with nodeBody()?
     *
     * @return
     */
    private static String nodePublicIpBody() {
        return "{\n"
                + "  \"kind\": \"Node\",\n"
                + "  \"apiVersion\": \"v1\",\n"
                + "  \"metadata\": {\n"
                + "    \"name\": \"gke-rafal-test-cluster-default-pool-7053600e-xd63\",\n"
                + "    \"selfLink\": \"/api/v1/nodes/gke-rafal-test-cluster-default-pool-7053600e-xd63\",\n"
                + "    \"uid\": \"121b756e-14e6-11e9-948d-42010a8001ed\",\n"
                + "    \"resourceVersion\": \"9347\",\n"
                + "    \"creationTimestamp\": \"2019-01-10T14:43:19Z\",\n"
                + "    \"labels\": {\n"
                + "      \"beta.kubernetes.io/arch\": \"amd64\",\n"
                + "      \"beta.kubernetes.io/fluentd-ds-ready\": \"true\",\n"
                + "      \"beta.kubernetes.io/instance-type\": \"n1-standard-1\",\n"
                + "      \"beta.kubernetes.io/os\": \"linux\",\n"
                + "      \"cloud.google.com/gke-nodepool\": \"default-pool\",\n"
                + "      \"cloud.google.com/gke-os-distribution\": \"cos\",\n"
                + "      \"failure-domain.beta.kubernetes.io/region\": \"us-central1\",\n"
                + "      \"failure-domain.beta.kubernetes.io/zone\": \"us-central1-a\",\n"
                + "      \"kubernetes.io/hostname\": \"gke-rafal-test-cluster-default-pool-7053600e-xd63\"\n"
                + "    },\n"
                + "    \"annotations\": {\n"
                + "      \"node.alpha.kubernetes.io/ttl\": \"0\",\n"
                + "      \"volumes.kubernetes.io/controller-managed-attach-detach\": \"true\"\n"
                + "    }\n"
                + "  },\n"
                + "  \"spec\": {\n"
                + "    \"podCIDR\": \"10.16.0.0/24\",\n"
                + "    \"externalID\": \"5236188219805722435\",\n"
                + "    \"providerID\": \"gce://hazelcast-33/us-central1-a/gke-rafal-test-cluster-default-pool-7053600e-xd63\"\n"
                + "  },\n"
                + "  \"status\": {\n"
                + "    \"capacity\": {\n"
                + "      \"cpu\": \"1\",\n"
                + "      \"ephemeral-storage\": \"98868448Ki\",\n"
                + "      \"hugepages-2Mi\": \"0\",\n"
                + "      \"memory\": \"3787604Ki\",\n"
                + "      \"pods\": \"110\"\n"
                + "    },\n"
                + "    \"allocatable\": {\n"
                + "      \"cpu\": \"940m\",\n"
                + "      \"ephemeral-storage\": \"47093746742\",\n"
                + "      \"hugepages-2Mi\": \"0\",\n"
                + "      \"memory\": \"2702164Ki\",\n"
                + "      \"pods\": \"110\"\n"
                + "    },\n"
                + "    \"conditions\": [\n"
                + "      {\n"
                + "        \"type\": \"FrequentUnregisterNetDevice\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:13Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:47:34Z\",\n"
                + "        \"reason\": \"UnregisterNetDevice\",\n"
                + "        \"message\": \"node is functioning properly\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"KernelDeadlock\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:13Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:42:33Z\",\n"
                + "        \"reason\": \"KernelHasNoDeadlock\",\n"
                + "        \"message\": \"kernel has no deadlock\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"NetworkUnavailable\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T14:43:41Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:41Z\",\n"
                + "        \"reason\": \"RouteCreated\",\n"
                + "        \"message\": \"RouteController created a route\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"OutOfDisk\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:10Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:19Z\",\n"
                + "        \"reason\": \"KubeletHasSufficientDisk\",\n"
                + "        \"message\": \"kubelet has sufficient disk space available\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"MemoryPressure\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:10Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:19Z\",\n"
                + "        \"reason\": \"KubeletHasSufficientMemory\",\n"
                + "        \"message\": \"kubelet has sufficient memory available\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"DiskPressure\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:10Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:19Z\",\n"
                + "        \"reason\": \"KubeletHasNoDiskPressure\",\n"
                + "        \"message\": \"kubelet has no disk pressure\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"PIDPressure\",\n"
                + "        \"status\": \"False\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:10Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:19Z\",\n"
                + "        \"reason\": \"KubeletHasSufficientPID\",\n"
                + "        \"message\": \"kubelet has sufficient PID available\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"Ready\",\n"
                + "        \"status\": \"True\",\n"
                + "        \"lastHeartbeatTime\": \"2019-01-10T16:01:10Z\",\n"
                + "        \"lastTransitionTime\": \"2019-01-10T14:43:39Z\",\n"
                + "        \"reason\": \"KubeletReady\",\n"
                + "        \"message\": \"kubelet is posting ready status. AppArmor enabled\"\n"
                + "      }\n"
                + "    ],\n"
                + "    \"addresses\": [\n"
                + "      {\n"
                + "        \"type\": \"InternalIP\",\n"
                + "        \"address\": \"10.240.0.21\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"ExternalIP\",\n"
                + "        \"address\": \"104.154.201.69\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"type\": \"Hostname\",\n"
                + "        \"address\": \"gke-rafal-test-cluster-default-pool-7053600e-xd63\"\n"
                + "      }\n"
                + "    ],\n"
                + "    \"daemonEndpoints\": {\n"
                + "      \"kubeletEndpoint\": {\n"
                + "        \"Port\": 10250\n"
                + "      }\n"
                + "    },\n"
                + "    \"nodeInfo\": {\n"
                + "      \"machineID\": \"71d2c8b7948ac46a4df79018bc4d74f7\",\n"
                + "      \"systemUUID\": \"71D2C8B7-948A-C46A-4DF7-9018BC4D74F7\",\n"
                + "      \"bootID\": \"eea969d4-a4b7-4b6c-abb7-16618a0518a7\",\n"
                + "      \"kernelVersion\": \"4.14.65+\",\n"
                + "      \"osImage\": \"Container-Optimized OS from Google\",\n"
                + "      \"containerRuntimeVersion\": \"docker://17.3.2\",\n"
                + "      \"kubeletVersion\": \"v1.10.9-gke.5\",\n"
                + "      \"kubeProxyVersion\": \"v1.10.9-gke.5\",\n"
                + "      \"operatingSystem\": \"linux\",\n"
                + "      \"architecture\": \"amd64\"\n"
                + "    },\n"
                + "    \"images\": [\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"gcr.io/stackdriver-agents/stackdriver-logging-agent@sha256:a33f69d0034fdce835a1eb7df8a051ea74323f3fc30d911bbd2e3f2aef09fc93\",\n"
                + "          \"gcr.io/stackdriver-agents/stackdriver-logging-agent:0.3-1.5.34-1-k8s-1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 554981103\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/node-problem-detector@sha256:f95cab985c26b2f46e9bd43283e0bfa88860c14e0fb0649266babe8b65e9eb2b\",\n"
                + "          \"k8s.gcr.io/node-problem-detector:v0.4.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 286572743\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/fluentd-elasticsearch@sha256:b8c94527b489fb61d3d81ce5ad7f3ddbb7be71e9620a3a36e2bede2f2e487d73\",\n"
                + "          \"k8s.gcr.io/fluentd-elasticsearch:v2.0.4\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 135716379\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"hazelcast/hazelcast@sha256:4ab4f2156111e2210f1ead39afe81a37c16f428f57e3c6bdd6645cf65dc7a194\",\n"
                + "          \"hazelcast/hazelcast:3.11\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 123250375\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/fluentd-gcp-scaler@sha256:457a13df66534b94bab627c4c2dc2df0ee5153a5d0f0afd27502bd46bd8da81d\",\n"
                + "          \"k8s.gcr.io/fluentd-gcp-scaler:0.5\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 103488147\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"gcr.io/google_containers/kube-proxy:v1.10.9-gke.5\",\n"
                + "          \"k8s.gcr.io/kube-proxy:v1.10.9-gke.5\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 103149276\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/kubernetes-dashboard-amd64@sha256:dc4026c1b595435ef5527ca598e1e9c4343076926d7d62b365c44831395adbd0\",\n"
                + "          \"k8s.gcr.io/kubernetes-dashboard-amd64:v1.8.3\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 102319441\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/event-exporter@sha256:7f9cd7cb04d6959b0aa960727d04fa86759008048c785397b7b0d9dff0007516\",\n"
                + "          \"k8s.gcr.io/event-exporter:v0.2.3\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 94171943\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/prometheus-to-sd@sha256:6c0c742475363d537ff059136e5d5e4ab1f512ee0fd9b7ca42ea48bc309d1662\",\n"
                + "          \"k8s.gcr.io/prometheus-to-sd:v0.3.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 88077694\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/kube-addon-manager@sha256:3519273916ba45cfc9b318448d4629819cb5fbccbb0822cce054dd8c1f68cb60\",\n"
                + "          \"k8s.gcr.io/kube-addon-manager:v8.6\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 78384272\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/heapster-amd64@sha256:fc33c690a3a446de5abc24b048b88050810a58b9e4477fa763a43d7df029301a\",\n"
                + "          \"k8s.gcr.io/heapster-amd64:v1.5.3\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 75318342\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/rescheduler@sha256:66a900b01c70d695e112d8fa7779255640aab77ccc31f2bb661e6c674fe0d162\",\n"
                + "          \"k8s.gcr.io/rescheduler:v0.3.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 74659350\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/ingress-gce-glbc-amd64@sha256:31d36bbd9c44caffa135fc78cf0737266fcf25e3cf0cd1c2fcbfbc4f7309cc52\",\n"
                + "          \"k8s.gcr.io/ingress-gce-glbc-amd64:v1.1.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 67801919\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"gcr.io/google-containers/prometheus-to-sd@sha256:be220ec4a66275442f11d420033c106bb3502a3217a99c806eef3cf9858788a2\",\n"
                + "          \"gcr.io/google-containers/prometheus-to-sd:v0.2.3\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 55342106\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/cpvpa-amd64@sha256:cfe7b0a11c9c8e18c87b1eb34fef9a7cbb8480a8da11fc2657f78dbf4739f869\",\n"
                + "          \"k8s.gcr.io/cpvpa-amd64:v0.6.0\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 51785854\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/cluster-proportional-autoscaler-amd64@sha256:003f98d9f411ddfa6ff6d539196355e03ddd69fa4ed38c7ffb8fec6f729afe2d\",\n"
                + "          \"k8s.gcr.io/cluster-proportional-autoscaler-amd64:1.1.2-r2\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 49648481\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/ip-masq-agent-amd64@sha256:1ffda57d87901bc01324c82ceb2145fe6a0448d3f0dd9cb65aa76a867cd62103\",\n"
                + "          \"k8s.gcr.io/ip-masq-agent-amd64:v2.1.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 49612505\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/k8s-dns-kube-dns-amd64@sha256:b99fc3eee2a9f052f7eb4cc00f15eb12fc405fa41019baa2d6b79847ae7284a8\",\n"
                + "          \"k8s.gcr.io/k8s-dns-kube-dns-amd64:1.14.10\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 49549457\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/metrics-server-amd64@sha256:49a9f12f7067d11f42c803dbe61ed2c1299959ad85cb315b25ff7eef8e6b8892\",\n"
                + "          \"k8s.gcr.io/metrics-server-amd64:v0.2.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 42541759\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/k8s-dns-sidecar-amd64@sha256:4f1ab957f87b94a5ec1edc26fae50da2175461f00afecf68940c4aa079bd08a4\",\n"
                + "          \"k8s.gcr.io/k8s-dns-sidecar-amd64:1.14.10\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 41635309\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/k8s-dns-dnsmasq-nanny-amd64@sha256:bbb2a290a568125b3b996028958eb773f33b5b87a6b37bf38a28f8b62dddb3c8\",\n"
                + "          \"k8s.gcr.io/k8s-dns-dnsmasq-nanny-amd64:1.14.10\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 40372149\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/addon-resizer@sha256:507aa9845ecce1fdde4d61f530c802f4dc2974c700ce0db7730866e442db958d\",\n"
                + "          \"k8s.gcr.io/addon-resizer:1.8.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 32968591\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/metadata-proxy@sha256:5be758058e67b578f7041498e2daca46ccd7426bc602d35ed0f50bd4a3659d50\",\n"
                + "          \"k8s.gcr.io/metadata-proxy:v0.1.10\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 8953717\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/defaultbackend@sha256:865b0c35e6da393b8e80b7e3799f777572399a4cff047eb02a81fa6e7a48ed4b\",\n"
                + "          \"k8s.gcr.io/defaultbackend:1.4\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 4844064\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/busybox@sha256:545e6a6310a27636260920bc07b994a299b6708a1b26910cfefd335fdfb60d2b\",\n"
                + "          \"k8s.gcr.io/busybox:1.27\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 1129289\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/busybox@sha256:4bdd623e848417d96127e16037743f0cd8b528c026e9175e22a84f639eca58ff\",\n"
                + "          \"k8s.gcr.io/busybox:1.24\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 1113554\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"asia.gcr.io/google-containers/pause-amd64@sha256:163ac025575b775d1c0f9bf0bdd0f086883171eb475b5068e7defa4ca9e76516\",\n"
                + "          \"eu.gcr.io/google-containers/pause-amd64@sha256:163ac025575b775d1c0f9bf0bdd0f086883171eb475b5068e7defa4ca9e76516\",\n"
                + "          \"gcr.io/google-containers/pause-amd64@sha256:163ac025575b775d1c0f9bf0bdd0f086883171eb475b5068e7defa4ca9e76516\",\n"
                + "          \"asia.gcr.io/google-containers/pause-amd64:3.0\",\n"
                + "          \"eu.gcr.io/google-containers/pause-amd64:3.0\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 746888\n"
                + "      },\n"
                + "      {\n"
                + "        \"names\": [\n"
                + "          \"k8s.gcr.io/pause-amd64@sha256:59eec8837a4d942cc19a52b8c09ea75121acc38114a2c68b98983ce9356b8610\",\n"
                + "          \"k8s.gcr.io/pause-amd64:3.1\"\n"
                + "        ],\n"
                + "        \"sizeBytes\": 742472\n"
                + "      }\n"
                + "    ]\n"
                + "  }\n"
                + "}";
    }

    /**
     * Real response recorded from the Kubernetes API.
     */
    private static String forbiddenBody() {
        return "Forbidden!Configured service account doesn't have access. Service account may have been revoked. "
                + "endpoints is forbidden: User \"system:serviceaccount:default:default\" cannot list endpoints "
                + "in the namespace \"default\"";
    }

    private static String malformedBody() {
        return "malformed response";
    }

    private static List<String> extractPrivateIpPortIsReady(List<Endpoint> addresses) {
        List<String> result = new ArrayList<String>();
        for (Endpoint address : addresses) {
            String ip = address.getPrivateAddress().getIp();
            Integer port = address.getPrivateAddress().getPort();
            boolean isReady = address.isReady();
            result.add(toString(ip, port, isReady));
        }
        return result;
    }

    private static String toString(String host, Integer port, boolean isReady) {
        return String.format("%s:%s:%s", host, port, isReady);
    }

    private static List<String> extractPublicIpPortIsReady(List<Endpoint> addresses) {
        List<String> result = new ArrayList<String>();
        for (Endpoint address : addresses) {
            String ip = address.getPublicAddress().getIp();
            Integer port = address.getPublicAddress().getPort();
            boolean isReady = address.isReady();
            result.add(toString(ip, port, isReady));
        }
        return result;
    }

    private static String ipPort(String ip, Integer port) {
        return String.format("%s:%s", ip, port);
    }
}
