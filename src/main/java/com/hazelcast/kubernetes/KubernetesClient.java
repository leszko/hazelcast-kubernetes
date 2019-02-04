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

import java.util.List;
import java.util.Map;

/**
 * Responsible for connecting to the Kubernetes API.
 */
interface KubernetesClient {

    /**
     * Retrieves POD addresses for all services in the given {@code namespace}.
     *
     * @param namespace namespace name
     * @return all POD addresses from the given {@code namespace}
     * @see <a href="https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/#list-143">Kubernetes Endpoint API</a>
     */
    Endpoints endpoints(String namespace);

    /**
     * Retrieves POD addresses for all services in the given {@code namespace} filtered by {@code serviceLabel}
     * and {@code serviceLabelValue}.
     *
     * @param namespace         namespace name
     * @param serviceLabel      label used to filter responses
     * @param serviceLabelValue label value used to filter responses
     * @return all POD addresses from the given {@code namespace} filtered by the label
     * @see <a href="https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/#list-143">Kubernetes Endpoint API</a>
     */
    Endpoints endpointsByLabel(String namespace, String serviceLabel, String serviceLabelValue);

    /**
     * Retrieves POD addresses from the given {@code namespace} and the given {@code endpointName}.
     *
     * @param namespace    namespace name
     * @param endpointName endpoint name
     * @return all POD addresses from the given {@code namespace} and the given {@code endpointName}
     * @see <a href="https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11/#list-143">Kubernetes Endpoint API</a>
     */
    Endpoints endpointsByName(String namespace, String endpointName);

    /**
     * Retrieves zone name for the given {@code namespace} and the given {@code podName}.
     * <p>
     * Note that the Kubernetes environment must provide such information as defined
     * <a href="https://kubernetes.io/docs/reference/kubernetes-api/labels-annotations-taints">here</a>.
     *
     * @param namespace namespace name
     * @param podName   POD name
     * @return zone name
     * @see <a href="https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.11">Kubernetes Endpoint API</a>
     */
    String zone(String namespace, String podName);

    /**
     * Result which stores the information about all addresses.
     */
    final class Endpoints {
        private final List<Endpoint> addresses;
        private final List<Endpoint> notReadyAddresses;

        Endpoints(List<Endpoint> addresses, List<Endpoint> notReadyAddresses) {
            this.addresses = addresses;
            this.notReadyAddresses = notReadyAddresses;
        }

        List<Endpoint> getAddresses() {
            return addresses;
        }

        List<Endpoint> getNotReadyAddresses() {
            return notReadyAddresses;
        }
    }

    /**
     * Result which stores the information about a single endpoint.
     */
    final class Endpoint {
        private final EndpointAddress privateAddress;
        private final EndpointAddress publicAddress;
        private final Map<String, Object> additionalProperties;

        Endpoint(EndpointAddress privateAddress, Map<String, Object> additionalProperties) {
            this.privateAddress = privateAddress;
            this.publicAddress = null;
            this.additionalProperties = additionalProperties;
        }

        Endpoint(EndpointAddress privateAddress, EndpointAddress publicAddress, Map<String, Object> additionalProperties) {
            this.privateAddress = privateAddress;
            this.publicAddress = publicAddress;
            this.additionalProperties = additionalProperties;
        }

        public EndpointAddress getPublicAddress() {
            return publicAddress;
        }

        public EndpointAddress getPrivateAddress() {
            return privateAddress;
        }

        Map<String, Object> getAdditionalProperties() {
            return additionalProperties;
        }
    }

    final class EndpointAddress {
        private final String ip;
        private final Integer port;

        public EndpointAddress(String ip, Integer port) {
            this.ip = ip;
            this.port = port;
        }

        public String getIp() {
            return ip;
        }

        public Integer getPort() {
            return port;
        }
    }
}
