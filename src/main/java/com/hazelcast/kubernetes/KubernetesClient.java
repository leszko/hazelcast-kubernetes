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

import com.hazelcast.internal.json.Json;
import com.hazelcast.internal.json.JsonArray;
import com.hazelcast.internal.json.JsonObject;
import com.hazelcast.internal.json.JsonValue;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static java.util.Collections.EMPTY_MAP;

/**
 * Responsible for connecting to the Kubernetes API.
 *
 * @see <a href="https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/">Kubernetes API</a>
 */
class KubernetesClient {
    private static final ILogger LOGGER = Logger.getLogger(KubernetesClient.class);
    private static final int RETRIES = 1;
    private static final List<String> NON_RETRYABLE_KEYWORDS = Arrays.asList("\"reason\":\"Forbidden\"");

    private final String namespace;
    private final String kubernetesMaster;
    private final String apiToken;
    private final String caCertificate;

    KubernetesClient(String namespace, String kubernetesMaster, String apiToken, String caCertificate) {
        this.namespace = namespace;
        this.kubernetesMaster = kubernetesMaster;
        this.apiToken = apiToken;
        this.caCertificate = caCertificate;
    }

    /**
     * Retrieves POD addresses in the specified {@code namespace}.
     *
     * @return all POD addresses
     * @see <a href="https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/#list-143">Kubernetes Endpoint API</a>
     */
    List<Endpoint> endpoints() {
        String urlString = String.format("%s/api/v1/namespaces/%s/pods", kubernetesMaster, namespace);
        return enrichPublicAddresses(parsePodsList(callGet(urlString)));
    }

    /**
     * Retrieves POD addresses for all services in the specified {@code namespace} filtered by {@code serviceLabel}
     * and {@code serviceLabelValue}.
     *
     * @param serviceLabel      label used to filter responses
     * @param serviceLabelValue label value used to filter responses
     * @return all POD addresses from the specified {@code namespace} filtered by the label
     * @see <a href="https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/#list-143">Kubernetes Endpoint API</a>
     */
    List<Endpoint> endpointsByLabel(String serviceLabel, String serviceLabelValue) {
        String param = String.format("labelSelector=%s=%s", serviceLabel, serviceLabelValue);
        String urlString = String.format("%s/api/v1/namespaces/%s/endpoints?%s", kubernetesMaster, namespace, param);
        return enrichPublicAddresses(parseEndpointsList(callGet(urlString)));
    }

    /**
     * Retrieves POD addresses from the specified {@code namespace} and the given {@code endpointName}.
     *
     * @param endpointName endpoint name
     * @return all POD addresses from the specified {@code namespace} and the given {@code endpointName}
     * @see <a href="https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/#list-143">Kubernetes Endpoint API</a>
     */
    List<Endpoint> endpointsByName(String endpointName) {
        String urlString = String.format("%s/api/v1/namespaces/%s/endpoints/%s", kubernetesMaster, namespace, endpointName);
        return enrichPublicAddresses(parseEndpoints(callGet(urlString)));
    }

    /**
     * Retrieves zone name for the specified {@code namespace} and the given {@code podName}.
     * <p>
     * Note that the Kubernetes environment must provide such information as defined
     * <a href="https://kubernetes.io/docs/reference/kubernetes-api/labels-annotations-taints">here</a>.
     *
     * @param podName POD name
     * @return zone name
     * @see <a href="https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11">Kubernetes Endpoint API</a>
     */
    String zone(String podName) {
        String podUrlString = String.format("%s/api/v1/namespaces/%s/pods/%s", kubernetesMaster, namespace, podName);
        JsonObject podJson = callGet(podUrlString);
        String nodeName = parseNodeName(podJson);

        String nodeUrlString = String.format("%s/api/v1/nodes/%s", kubernetesMaster, nodeName);
        JsonObject nodeJson = callGet(nodeUrlString);
        return parseZone(nodeJson);
    }

    private static List<Endpoint> parsePodsList(JsonObject json) {
        List<Endpoint> addresses = new ArrayList<Endpoint>();

        for (JsonValue item : toJsonArray(json.get("items"))) {
            JsonObject status = item.asObject().get("status").asObject();
            String ip = toString(status.get("podIP"));
            if (ip != null) {
                Integer port = getPortFromPodItem(item);
                addresses.add(new Endpoint(new EndpointAddress(ip, port), isReady(status)));
            }
        }
        return addresses;
    }

    private static Integer getPortFromPodItem(JsonValue item) {
        JsonArray containers = toJsonArray(item.asObject().get("spec").asObject().get("containers"));
        // If multiple containers are in one POD, then use the default Hazelcast port from the configuration.
        if (containers.size() == 1) {
            JsonValue container = containers.get(0);
            JsonArray ports = toJsonArray(container.asObject().get("ports"));
            // If multiple ports are exposed by a container, then use the default Hazelcast port from the configuration.
            if (ports.size() == 1) {
                JsonValue port = ports.get(0);
                JsonValue containerPort = port.asObject().get("containerPort");
                if (containerPort != null && containerPort.isNumber()) {
                    return containerPort.asInt();
                }
            }
        }
        return null;
    }

    private static boolean isReady(JsonObject status) {
        for (JsonValue containerStatus : toJsonArray(status.get("containerStatuses"))) {
            // If multiple containers are in one POD, then each needs to be ready.
            if (!containerStatus.asObject().get("ready").asBoolean()) {
                return false;
            }
        }
        return true;
    }

    private static List<Endpoint> parseEndpointsList(JsonObject json) {
        List<Endpoint> endpoints = new ArrayList<Endpoint>();

        for (JsonValue item : toJsonArray(json.get("items"))) {
            endpoints.addAll(parseEndpoints(item));
        }

        return endpoints;
    }

    private static List<Endpoint> parseEndpoints(JsonValue item) {
        List<Endpoint> addresses = new ArrayList<Endpoint>();

        for (JsonValue subset : toJsonArray(item.asObject().get("subsets"))) {
            Integer endpointPort = parseEndpointPort(subset);
            for (JsonValue address : toJsonArray(subset.asObject().get("addresses"))) {
                addresses.add(parseEntrypointAddress(address, endpointPort, true));
            }
            for (JsonValue address : toJsonArray(subset.asObject().get("notReadyAddresses"))) {
                addresses.add(parseEntrypointAddress(address, endpointPort, false));
            }
        }
        return addresses;
    }

    private static Integer parseEndpointPort(JsonValue subset) {
        JsonArray ports = toJsonArray(subset.asObject().get("ports"));
        if (ports.size() == 1) {
            JsonValue port = ports.get(0);
            return port.asObject().get("port").asInt();
        }
        return null;
    }

    private static Endpoint parseEntrypointAddress(JsonValue endpointAddressJson, Integer endpointPort, boolean isReady) {
        String ip = endpointAddressJson.asObject().get("ip").asString();
        Integer port = getPortFromEndpointAddress(endpointAddressJson, endpointPort);
        Map<String, Object> additionalProperties = parseAdditionalProperties(endpointAddressJson);
        return new Endpoint(new EndpointAddress(ip, port), isReady, additionalProperties);
    }

    private static Integer getPortFromEndpointAddress(JsonValue endpointAddressJson, Integer endpointPort) {
        JsonValue servicePort = endpointAddressJson.asObject().get("hazelcast-service-port");
        if (servicePort != null && servicePort.isNumber()) {
            return servicePort.asInt();
        }
        return endpointPort;
    }

    private static Map<String, Object> parseAdditionalProperties(JsonValue endpointAddressJson) {
        Set<String> knownFieldNames = new HashSet<String>(
                Arrays.asList("ip", "nodeName", "targetRef", "hostname", "hazelcast-service-port"));

        Map<String, Object> result = new HashMap<String, Object>();
        Iterator<JsonObject.Member> iter = endpointAddressJson.asObject().iterator();
        while (iter.hasNext()) {
            JsonObject.Member member = iter.next();
            if (!knownFieldNames.contains(member.getName())) {
                result.put(member.getName(), toString(member.getValue()));
            }
        }
        return result;
    }

    private static String parseZone(JsonObject json) {
        JsonObject labels = json.get("metadata").asObject().get("labels").asObject();
        JsonValue zone = labels.get("failure-domain.kubernetes.io/zone");
        if (zone != null) {
            return toString(zone);
        }
        return toString(labels.get("failure-domain.beta.kubernetes.io/zone"));
    }

    private static String parseNodeName(JsonObject json) {
        return toString(json.get("spec").asObject().get("nodeName"));
    }

    /**
     * Tries to add public addresses to the endpoints.
     * <p>
     * If it's not possible, then returns the input parameter.
     */
    private List<Endpoint> enrichPublicAddresses(List<Endpoint> endpoints) {
        try {
            String endpointsUrl = String.format("%s/api/v1/namespaces/%s/endpoints", kubernetesMaster, namespace);
            JsonObject endpointsResponse = callGet(endpointsUrl);

            List<EndpointAddress> privateAddresses = extractPrivateAddresses(endpoints);
            Map<EndpointAddress, String> services = parseServices(endpointsResponse, privateAddresses);
            Map<EndpointAddress, String> nodes = parseNodes(endpointsResponse, privateAddresses);

            Map<EndpointAddress, Integer> nodePorts = nodePortsFor(services);
            Map<EndpointAddress, String> nodePublicIps = publicIpsFor(nodes);

            return createEndpoints(endpoints, nodePublicIps, nodePorts);
        } catch (Exception e) {
            LOGGER.finest(e);
            LOGGER.warning(
                    "Cannot fetch public IPs of Hazelcast Member PODs, won't be able to use Hazelcast Smart Client from outside the Kubernetes network");
            return endpoints;
        }
    }

    private static List<EndpointAddress> extractPrivateAddresses(List<Endpoint> endpoints) {
        List<EndpointAddress> result = new ArrayList<EndpointAddress>();
        for (Endpoint endpoint : endpoints) {
            result.add(endpoint.getPrivateAddress());
        }
        return result;
    }

    private static Map<EndpointAddress, String> parseServices(JsonObject endpointsJson,
                                                              List<EndpointAddress> privateAddresses) {
        Map<EndpointAddress, String> result = new HashMap<EndpointAddress, String>();
        Set<EndpointAddress> left = new HashSet<EndpointAddress>(privateAddresses);
        for (JsonValue item : toJsonArray(endpointsJson.get("items"))) {
            String service = toString(item.asObject().get("metadata").asObject().get("name"));
            List<Endpoint> endpoints = parseEndpoints(item);
            // Service must point to exactly one endpoint address, otherwise the public IP would be ambiguous.
            if (endpoints.size() == 1) {
                EndpointAddress address = endpoints.get(0).getPrivateAddress();
                if (left.contains(address)) {
                    result.put(address, service);
                    left.remove(address);
                }
            }
        }
        if (!left.isEmpty()) {
            // At least one Hazelcast Member POD does not have a corresponding service.
            throw new KubernetesClientException(String.format("Cannot fetch services dedicated to the following PODs: %s", left));
        }
        return result;
    }

    private Map<EndpointAddress, String> parseNodes(JsonObject json, List<EndpointAddress> pods) {
        Map<EndpointAddress, String> result = new HashMap<EndpointAddress, String>();
        Set<EndpointAddress> left = new HashSet<EndpointAddress>(pods);
        for (JsonValue item : toJsonArray(json.get("items"))) {
            for (JsonValue subset : toJsonArray(item.asObject().get("subsets"))) {
                List<Integer> ports = new ArrayList<Integer>();
                for (JsonValue port : toJsonArray(subset.asObject().get("ports"))) {
                    ports.add(new Integer(port.asObject().get("port").asInt()));
                }
                for (JsonValue address : toJsonArray(subset.asObject().get("addresses"))) {
                    String ip = address.asObject().get("ip").asString();
                    String nodeName = toString(address.asObject().get("nodeName"));
                    for (Integer port : ports) {
                        EndpointAddress pod = new EndpointAddress(ip, port);
                        if (pods.contains(pod)) {
                            result.put(pod, nodeName);
                            left.remove(pod);
                        }
                    }
                }
                for (JsonValue address : toJsonArray(subset.asObject().get("notReadyAddresses"))) {
                    String ip = address.asObject().get("ip").asString();
                    String nodeName = toString(address.asObject().get("nodeName"));
                    for (Integer port : ports) {
                        EndpointAddress pod = new EndpointAddress(ip, port);
                        if (pods.contains(pod)) {
                            result.put(pod, nodeName);
                            left.remove(pod);
                        }
                    }
                }
            }
        }
        if (!left.isEmpty()) {
            throw new RuntimeException("!!!!");
        }
        return result;
    }

    private Map<EndpointAddress, Integer> nodePortsFor(Map<EndpointAddress, String> services) {
        Map<EndpointAddress, Integer> result = new HashMap<EndpointAddress, Integer>();
        Map<String, Integer> nodePortByService = new HashMap<String, Integer>();
        for (EndpointAddress privateAddress : services.keySet()) {
            String service = services.get(privateAddress);
            if (nodePortByService.containsKey(service)) {
                result.put(privateAddress, nodePortByService.get(service));
            } else {
                String urlString = String.format("%s/api/v1/namespaces/%s/services/%s", kubernetesMaster, namespace, service);
                Integer nodePort = parseNodePort(callGet(urlString));
                result.put(privateAddress, nodePort);
                nodePortByService.put(service, nodePort);
            }
        }
        return result;
    }

    private Integer parseNodePort(JsonObject json) {
        JsonArray ports = toJsonArray(json.get("spec").asObject().get("ports"));
        if (ports.size() != 1) {
            throw new RuntimeException("!!!!");
        }
        JsonValue port = ports.get(0);
        return port.asObject().get("nodePort").asInt();
    }

    private Map<EndpointAddress, String> publicIpsFor(Map<EndpointAddress, String> nodes) {
        Map<EndpointAddress, String> result = new HashMap<EndpointAddress, String>();
        Map<String, String> publicIpByNode = new HashMap<String, String>();
        for (EndpointAddress privateAddress : nodes.keySet()) {
            String node = nodes.get(privateAddress);
            if (publicIpByNode.containsKey(node)) {
                result.put(privateAddress, publicIpByNode.get(node));
            } else {
                String urlString = String.format("%s/api/v1/nodes/%s", kubernetesMaster, node);
                String publicIp = parsePublicIp(callGet(urlString));
                result.put(privateAddress, publicIp);
                publicIpByNode.put(node, publicIp);
            }
        }
        return result;
    }

    private String parsePublicIp(JsonObject json) {
        for (JsonValue address : toJsonArray(json.get("status").asObject().get("addresses"))) {
            if ("ExternalIP".equals(address.asObject().get("type").asString())) {
                return address.asObject().get("address").asString();
            }
        }
        return null;
    }

    private static List<Endpoint> createEndpoints(List<Endpoint> endpoints, Map<EndpointAddress, String> nodePublicIps,
                                                  Map<EndpointAddress, Integer> nodePorts) {
        List<Endpoint> result = new ArrayList<Endpoint>();
        for (Endpoint endpoint : endpoints) {
            EndpointAddress privateAddress = endpoint.getPrivateAddress();
            EndpointAddress publicAddress = new EndpointAddress(nodePublicIps.get(privateAddress),
                    nodePorts.get(privateAddress));
            result.add(new Endpoint(privateAddress, publicAddress, endpoint.isReady(), endpoint.getAdditionalProperties()));
        }
        return result;
    }

    /**
     * Makes a REST call to Kubernetes API and returns the result JSON.
     *
     * @param urlString Kubernetes API REST endpoint
     * @return parsed JSON
     * @throws KubernetesClientException if Kubernetes API didn't respond with 200 and a valid JSON content
     */
    private JsonObject callGet(final String urlString) {
        try {
            return RetryUtils.retry(new Callable<JsonObject>() {
                @Override
                public JsonObject call() {
                    return Json
                            .parse(RestClient.create(urlString).withHeader("Authorization", String.format("Bearer %s", apiToken))
                                             .withCaCertificate(caCertificate)
                                             .get())
                            .asObject();
                }
            }, RETRIES, NON_RETRYABLE_KEYWORDS);
        } catch (Exception e) {
            throw new KubernetesClientException("Failure in KubernetesClient", e);
        }
    }

    private static JsonArray toJsonArray(JsonValue jsonValue) {
        if (jsonValue == null || jsonValue.isNull()) {
            return new JsonArray();
        } else {
            return jsonValue.asArray();
        }
    }

    private static String toString(JsonValue jsonValue) {
        if (jsonValue == null || jsonValue.isNull()) {
            return null;
        } else if (jsonValue.isString()) {
            return jsonValue.asString();
        } else {
            return jsonValue.toString();
        }
    }

    /**
     * Result which stores the information about a single endpoint.
     */
    final static class Endpoint {
        private final EndpointAddress privateAddress;
        private final EndpointAddress publicAddress;
        private final boolean isReady;
        private final Map<String, Object> additionalProperties;

        Endpoint(EndpointAddress privateAddress, boolean isReady) {
            this.privateAddress = privateAddress;
            this.publicAddress = null;
            this.isReady = isReady;
            this.additionalProperties = EMPTY_MAP;
        }

        Endpoint(EndpointAddress privateAddress, boolean isReady, Map<String, Object> additionalProperties) {
            this.privateAddress = privateAddress;
            this.publicAddress = null;
            this.isReady = isReady;
            this.additionalProperties = additionalProperties;
        }

        Endpoint(EndpointAddress privateAddress, EndpointAddress publicAddress, boolean isReady,
                 Map<String, Object> additionalProperties) {
            this.privateAddress = privateAddress;
            this.publicAddress = publicAddress;
            this.isReady = isReady;
            this.additionalProperties = additionalProperties;
        }

        EndpointAddress getPublicAddress() {
            return publicAddress;
        }

        EndpointAddress getPrivateAddress() {
            return privateAddress;
        }

        boolean isReady() {
            return isReady;
        }

        Map<String, Object> getAdditionalProperties() {
            return additionalProperties;
        }
    }

    final static class EndpointAddress {
        private final String ip;
        private final Integer port;

        EndpointAddress(String ip, Integer port) {
            this.ip = ip;
            this.port = port;
        }

        String getIp() {
            return ip;
        }

        Integer getPort() {
            return port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            EndpointAddress address = (EndpointAddress) o;

            if (ip != null ? !ip.equals(address.ip) : address.ip != null) {
                return false;
            }
            return port != null ? port.equals(address.port) : address.port == null;
        }

        @Override
        public int hashCode() {
            int result = ip != null ? ip.hashCode() : 0;
            result = 31 * result + (port != null ? port.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return String.format("%s:%s", ip, port);
        }
    }
}
