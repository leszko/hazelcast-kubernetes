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
import com.hazelcast.nio.IOUtil;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import static java.util.Collections.EMPTY_MAP;

/**
 * Responsible for connecting to the Kubernetes API.
 * <p>
 * Note: This client should always be used from inside Kubernetes since it depends on the CA Cert file, which exists
 * in the POD filesystem.
 *
 * @see <a href="https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/">Kubernetes API</a>
 */
class DefaultKubernetesClient
        implements KubernetesClient {
    private static final int HTTP_OK = 200;

    private final String kubernetesMaster;
    private final String apiToken;
    private final String caCertificate;

    DefaultKubernetesClient(String kubernetesMaster, String apiToken, String caCertificate) {
        this.kubernetesMaster = kubernetesMaster;
        this.apiToken = apiToken;
        this.caCertificate = caCertificate;
    }

    @Override
    public Endpoints endpoints(String namespace) {
        String urlString = String.format("%s/api/v1/namespaces/%s/pods", kubernetesMaster, namespace);
        return enrichWithPublicAddress(namespace, parsePodsList(callGet(urlString)));

    }

    @Override
    public Endpoints endpointsByLabel(String namespace, String serviceLabel, String serviceLabelValue) {
        String param = String.format("labelSelector=%s=%s", serviceLabel, serviceLabelValue);
        String urlString = String.format("%s/api/v1/namespaces/%s/endpoints?%s", kubernetesMaster, namespace, param);
        return enrichWithPublicAddress(namespace, parseEndpointsList(callGet(urlString)));
    }

    @Override
    public Endpoints endpointsByName(String namespace, String endpointName) {
        String urlString = String.format("%s/api/v1/namespaces/%s/endpoints/%s", kubernetesMaster, namespace, endpointName);
        return enrichWithPublicAddress(namespace, parseEndpoint(callGet(urlString)));
    }

    @Override
    public String zone(String namespace, String podName) {
        String podUrlString = String.format("%s/api/v1/namespaces/%s/pods/%s", kubernetesMaster, namespace, podName);
        JsonObject podJson = callGet(podUrlString);
        String nodeName = parseNodeName(podJson);

        String nodeUrlString = String.format("%s/api/v1/nodes/%s", kubernetesMaster, nodeName);
        JsonObject nodeJson = callGet(nodeUrlString);
        return parseZone(nodeJson);
    }

    private Endpoints enrichWithPublicAddress(String namespace, Endpoints endpoints) {
        try {
            List<PodIpPort> pods = extractPodsFrom(endpoints);

            String urlString = String.format("%s/api/v1/namespaces/%s/endpoints", kubernetesMaster, namespace);
            JsonObject endpointsResult = callGet(urlString);

            Map<PodIpPort, String> podToServiceName = parsePodToServiceName(endpointsResult, pods);
            Map<PodIpPort, String> podToNodeName = parseEndpointsToNodeName(endpointsResult, pods);
            Map<String, String> serviceToNodePort = fetchNodePortFor(namespace, podToServiceName.values());
            Map<String, String> nodeToPublicIp = fetchPublicIpFor(podToNodeName.values());

            List<Endpoint> addresses = new ArrayList<Endpoint>();
            for (Endpoint endpoint : endpoints.getAddresses()) {
                addresses.add(createEndpointFrom(endpoint, podToServiceName, podToNodeName, serviceToNodePort, nodeToPublicIp));
            }
            List<Endpoint> notReadyAddresses = new ArrayList<Endpoint>();
            for (Endpoint endpoint : endpoints.getNotReadyAddresses()) {
                notReadyAddresses
                        .add(createEndpointFrom(endpoint, podToServiceName, podToNodeName, serviceToNodePort, nodeToPublicIp));
            }

            return new Endpoints(addresses, notReadyAddresses);
        } catch (Exception e) {
            e.printStackTrace();
            return endpoints;
        }
    }

    private static List<PodIpPort> extractPodsFrom(Endpoints endpoints) {
        List<PodIpPort> result = new ArrayList<PodIpPort>();
        for (Endpoint endpoint : endpoints.getAddresses()) {
            result.add(new PodIpPort(endpoint.getPrivateAddress().getIp(), endpoint.getPrivateAddress().getPort()));
        }
        for (Endpoint endpoint : endpoints.getNotReadyAddresses()) {
            result.add(new PodIpPort(endpoint.getPrivateAddress().getIp(), endpoint.getPrivateAddress().getPort()));
        }
        return result;
    }

    private static Map<PodIpPort, String> parsePodToServiceName(JsonObject json, List<PodIpPort> pods) {
        Map<PodIpPort, String> result = new HashMap<PodIpPort, String>();
        Set<PodIpPort> left = new HashSet<PodIpPort>(pods);
        for (JsonValue item : toJsonArray(json.get("items"))) {
            String serviceName = item.asObject().get("metadata").asObject().get("name").asString();
            List<PodIpPort> parsedPods = parsePods(item);
            if (parsedPods.size() == 1) {
                PodIpPort pod = parsedPods.get(0);
                if (pods.contains(pod)) {
                    String currentServiceName = result.get(pod);
                    if (currentServiceName == null || serviceName.length() > currentServiceName.length()) {
                        result.put(pod, serviceName);
                        left.remove(pod);
                    }
                }
            }
        }
        if (!left.isEmpty()) {
            throw new RuntimeException("!!!!");
        }
        return result;
    }

    private static List<PodIpPort> parsePods(JsonValue item) {
        List<PodIpPort> result = new ArrayList<PodIpPort>();
        for (JsonValue subset : toJsonArray(item.asObject().get("subsets"))) {
            List<String> ips = new ArrayList<String>();
            for (JsonValue address : toJsonArray(subset.asObject().get("addresses"))) {
                ips.add(address.asObject().get("ip").asString());
            }
            for (JsonValue address : toJsonArray(subset.asObject().get("notReadyAddresses"))) {
                ips.add(address.asObject().get("ip").asString());
            }
            List<Integer> ports = new ArrayList<Integer>();
            for (JsonValue port : toJsonArray(subset.asObject().get("ports"))) {
                ports.add(new Integer(port.asObject().get("port").asInt()));
            }
            for (String ip : ips) {
                for (Integer port : ports) {
                    result.add(new PodIpPort(ip, port));
                }
            }
        }
        return result;
    }

    private Map<PodIpPort, String> parseEndpointsToNodeName(JsonObject json, List<PodIpPort> pods) {
        Map<PodIpPort, String> result = new HashMap<PodIpPort, String>();
        Set<PodIpPort> left = new HashSet<PodIpPort>(pods);
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
                        PodIpPort pod = new PodIpPort(ip, port);
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
                        PodIpPort pod = new PodIpPort(ip, port);
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

    private Map<String, String> fetchNodePortFor(String namespace, Collection<String> serviceNames) {
        Map<String, String> result = new HashMap<String, String>();
        for (String serviceName : serviceNames) {
            if (!result.containsKey(serviceName)) {
                String urlString = String.format("%s/api/v1/namespaces/%s/services/%s", kubernetesMaster, namespace, serviceName);
                result.put(serviceName, parseNodePort(callGet(urlString)));
            }
        }
        return result;
    }

    private String parseNodePort(JsonObject json) {
        JsonArray ports = toJsonArray(json.get("spec").asObject().get("ports"));
        if (ports.size() != 1) {
            throw new RuntimeException("!!!!");
        }
        JsonValue port = ports.get(0);
        return toString(port.asObject().get("nodePort"));
    }

    private Map<String, String> fetchPublicIpFor(Collection<String> nodeNames) {
        Map<String, String> result = new HashMap<String, String>();
        for (String nodeName : nodeNames) {
            if (!result.containsKey(nodeName)) {
                String urlString = String.format("%s/api/v1/nodes/%s", kubernetesMaster, nodeName);
                result.put(nodeName, parsePublicIp(callGet(urlString)));
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

    private Endpoint createEndpointFrom(Endpoint endpoint, Map<PodIpPort, String> podToServiceName,
                                        Map<PodIpPort, String> podToNodeName, Map<String, String> serviceToNodePort,
                                        Map<String, String> nodeToPublicIp) {
        PodIpPort pod = new PodIpPort(endpoint.getPrivateAddress().getIp(), endpoint.getPrivateAddress().getPort());
        String publicIp = nodeToPublicIp.get(podToNodeName.get(pod));
        Integer publicPort = Integer.parseInt(serviceToNodePort.get(podToServiceName.get(pod)));
        return new Endpoint(endpoint.getPrivateAddress(), new EndpointAddress(publicIp, publicPort),
                endpoint.getAdditionalProperties());
    }

    private static class PodIpPort {
        private final String ip;
        private final Integer port;

        public PodIpPort(String ip, Integer port) {
            this.ip = ip;
            this.port = port;
        }

        public String getIp() {
            return ip;
        }

        public Integer getPort() {
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

            PodIpPort podIpPort = (PodIpPort) o;

            if (ip != null ? !ip.equals(podIpPort.ip) : podIpPort.ip != null) {
                return false;
            }
            return port != null ? port.equals(podIpPort.port) : podIpPort.port == null;
        }

        @Override
        public int hashCode() {
            int result = ip != null ? ip.hashCode() : 0;
            result = 31 * result + (port != null ? port.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "PodIpPort{" +
                    "ip='" + ip + '\'' +
                    ", port=" + port +
                    '}';
        }
    }

    private JsonObject callGet(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            if (connection instanceof HttpsURLConnection) {
                ((HttpsURLConnection) connection).setSSLSocketFactory(buildSslSocketFactory());
            }
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", String.format("Bearer %s", apiToken));

            if (connection.getResponseCode() != HTTP_OK) {
                throw new KubernetesClientException(String.format("Failure executing: GET at: %s. Message: %s,", urlString,
                        read(connection.getErrorStream())));
            }
            String read = read(connection.getInputStream());
            return Json.parse(read).asObject();
        } catch (Exception e) {
            throw new KubernetesClientException("Failure in KubernetesClient", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String read(InputStream stream) {
        Scanner scanner = new Scanner(stream, "UTF-8");
        scanner.useDelimiter("\\Z");
        return scanner.next();
    }

    private static Endpoints parsePodsList(JsonObject json) {
        List<Endpoint> addresses = new ArrayList<Endpoint>();
        List<Endpoint> notReadyAddresses = new ArrayList<Endpoint>();

        for (JsonValue item : toJsonArray(json.get("items"))) {
            JsonObject status = item.asObject().get("status").asObject();
            String ip = toString(status.get("podIP"));
            if (ip != null) {
                Integer port = getPortFromPodItem(item);
                Endpoint address = new Endpoint(new EndpointAddress(ip, port), EMPTY_MAP);

                if (isReady(status)) {
                    addresses.add(address);
                } else {
                    notReadyAddresses.add(address);
                }
            }
        }

        return new Endpoints(addresses, notReadyAddresses);
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

    private static Endpoints parseEndpointsList(JsonObject json) {
        List<Endpoint> addresses = new ArrayList<Endpoint>();
        List<Endpoint> notReadyAddresses = new ArrayList<Endpoint>();

        for (JsonValue object : toJsonArray(json.get("items"))) {
            Endpoints endpoints = parseEndpoint(object);
            addresses.addAll(endpoints.getAddresses());
            notReadyAddresses.addAll(endpoints.getNotReadyAddresses());
        }

        return new Endpoints(addresses, notReadyAddresses);
    }

    private static Endpoints parseEndpoint(JsonValue endpointsJson) {
        List<Endpoint> addresses = new ArrayList<Endpoint>();
        List<Endpoint> notReadyAddresses = new ArrayList<Endpoint>();

        for (JsonValue subset : toJsonArray(endpointsJson.asObject().get("subsets"))) {
            Integer endpointPort = parseEndpointPort(subset);
            for (JsonValue address : toJsonArray(subset.asObject().get("addresses"))) {
                addresses.add(parseEntrypointAddress(address, endpointPort));
            }
            for (JsonValue notReadyAddress : toJsonArray(subset.asObject().get("notReadyAddresses"))) {
                notReadyAddresses.add(parseEntrypointAddress(notReadyAddress, endpointPort));
            }
        }
        return new Endpoints(addresses, notReadyAddresses);
    }

    private static Integer parseEndpointPort(JsonValue subset) {
        JsonArray ports = toJsonArray(subset.asObject().get("ports"));
        if (ports.size() == 1) {
            JsonValue port = ports.get(0);
            return port.asObject().get("port").asInt();
        }
        return null;
    }

    private static Endpoint parseEntrypointAddress(JsonValue endpointAddressJson, Integer endpointPort) {
        String ip = endpointAddressJson.asObject().get("ip").asString();
        Integer port = getPortFromEndpointAddress(endpointAddressJson, endpointPort);
        Map<String, Object> additionalProperties = parseAdditionalProperties(endpointAddressJson);
        return new Endpoint(new EndpointAddress(ip, port), additionalProperties);
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

    private static String parseNodeName(JsonObject json) {
        return toString(json.get("spec").asObject().get("nodeName"));
    }

    private static String parseZone(JsonObject json) {
        JsonObject labels = json.get("metadata").asObject().get("labels").asObject();
        JsonValue zone = labels.get("failure-domain.kubernetes.io/zone");
        if (zone != null) {
            return toString(zone);
        }
        return toString(labels.get("failure-domain.beta.kubernetes.io/zone"));
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
     * Builds SSL Socket Factory with the public CA Certificate from Kubernetes Master.
     */
    private SSLSocketFactory buildSslSocketFactory() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("ca", generateCertificate());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, tmf.getTrustManagers(), null);
            return context.getSocketFactory();

        } catch (Exception e) {
            throw new KubernetesClientException("Failure in generating SSLSocketFactory", e);
        }
    }

    /**
     * Generates CA Certificate from the default CA Cert file or from the externally provided "ca-certificate" property.
     */
    private Certificate generateCertificate()
            throws IOException, CertificateException {
        InputStream caInput = null;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            caInput = new ByteArrayInputStream(caCertificate.getBytes("UTF-8"));
            return cf.generateCertificate(caInput);
        } finally {
            IOUtil.closeResource(caInput);
        }
    }

}
