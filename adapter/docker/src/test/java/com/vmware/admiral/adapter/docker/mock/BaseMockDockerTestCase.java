/*
 * Copyright (c) 2016-2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.adapter.docker.mock;

import java.io.InputStream;
import java.net.URI;
import java.util.Properties;
import java.util.logging.Level;

import org.junit.After;
import org.junit.Before;

import com.vmware.admiral.adapter.common.ContainerHostOperationType;
import com.vmware.admiral.adapter.common.service.mock.MockTaskFactoryService;
import com.vmware.admiral.adapter.common.service.mock.MockTaskService.MockTaskState;
import com.vmware.admiral.adapter.docker.service.ContainerHostRequest;
import com.vmware.admiral.common.test.BaseTestCase;
import com.vmware.admiral.common.test.HostInitTestDcpServicesConfig;
import com.vmware.admiral.host.ComputeInitialBootService;
import com.vmware.admiral.host.HostInitCommonServiceConfig;
import com.vmware.admiral.host.HostInitComputeServicesConfig;
import com.vmware.admiral.host.HostInitPhotonModelServiceConfig;
import com.vmware.admiral.service.common.ServiceTaskCallback;
import com.vmware.admiral.service.common.SslTrustCertificateService;
import com.vmware.admiral.service.common.SslTrustCertificateService.SslTrustCertificateState;
import com.vmware.admiral.service.common.TaskServiceDocument;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.test.VerificationHost;
import com.vmware.xenon.services.common.AuthCredentialsService;
import com.vmware.xenon.services.common.AuthCredentialsService.AuthCredentialsServiceState;

/**
 * Add mock docker host and services
 */
public class BaseMockDockerTestCase extends BaseTestCase {
    // the test docker target can be overridden by setting the properties in
    // this properties file
    private static final String DOCKER_TEST_PROPERTIES_FILE = "/test-docker.properties";
    private static final String DOCKER_URI = "test.docker.uri";
    private static final String DOCKER_CLIENT_KEY = "test.docker.client.key";
    private static final String DOCKER_CLIENT_CERTIFICATE = "test.docker.client.certificate";
    private static final String DOCKER_SERVER_CERTIFICATE = "test.docker.server.certificate";
    protected static final long FUTURE_TIMEOUT_SECONDS = 10;
    protected static final String TASK_INFO_STAGE = TaskServiceDocument.FIELD_NAME_TASK_STAGE;

    protected VerificationHost mockDockerHost;
    protected URI dockerHostAdapterServiceUri;
    protected ComputeState dockerHostState;
    protected String testDockerCredentialsLink;
    protected String provisioningTaskLink;

    protected static URI dockerUri;
    protected static URI dockerVersionedUri;
    protected static AuthCredentialsServiceState dockerCredentials;
    protected static SslTrustCertificateState dockerTrust;
    protected static MockDockerCreateImageService mockDockerCreateImageService;

    @Before
    public void setUpMockDockerHost() throws Throwable {
        HostInitTestDcpServicesConfig.startServices(host);
        HostInitPhotonModelServiceConfig.startServices(host);
        HostInitCommonServiceConfig.startServices(host);
        HostInitComputeServicesConfig.startServices(host, false);
        waitForServiceAvailability(ComputeInitialBootService.SELF_LINK);
        waitForInitialBootServiceToBeSelfStopped(ComputeInitialBootService.SELF_LINK);

        // check if a properties file exist and use those details instead of
        // starting a mock server
        try (InputStream testPropertiesStream = BaseMockDockerTestCase.class
                .getResourceAsStream(DOCKER_TEST_PROPERTIES_FILE)) {
            if (testPropertiesStream != null) {
                Properties testProperties = new Properties();
                testProperties.load(testPropertiesStream);

                dockerUri = URI.create(getProperty(DOCKER_URI, testProperties));
                dockerCredentials = new AuthCredentialsServiceState();
                dockerCredentials.privateKeyId = getProperty(DOCKER_CLIENT_KEY,
                        testProperties);
                dockerCredentials.privateKey = getProperty(
                        DOCKER_CLIENT_CERTIFICATE, testProperties);
                dockerTrust = new SslTrustCertificateState();
                dockerTrust.certificate = getProperty(DOCKER_SERVER_CERTIFICATE, testProperties);
            } else {
                host.log(Level.WARNING, "Properties file %s not found",
                        DOCKER_TEST_PROPERTIES_FILE);
            }
        }

        // in case the properties file doesn't contain a valid URI start a mock
        // docker server
        synchronized (BaseMockDockerTestCase.class) {
            if (dockerUri == null || dockerUri.toString().isEmpty()) {
                startMockDockerHost();
                dockerUri = UriUtils.buildUri(mockDockerHost,
                        MockDockerPathConstants.BASE_PATH);
                dockerCredentials = new AuthCredentialsServiceState();
            }
        }

        dockerVersionedUri = UriUtils.extendUri(dockerUri, "v"
                + MockDockerPathConstants.API_VERSION);

        host.log("Using test docker URI: %s", dockerUri);

        System.setProperty("dcp.management.container.shell.availability.retry", "0");

        host.startService(
                Operation.createPost(UriUtils.buildUri(host, MockTaskFactoryService.SELF_LINK)),
                new MockTaskFactoryService());

    }

    @After
    public void tearDownMockDockerHost() {
        if (mockDockerHost != null) {
            mockDockerHost.tearDown();
        }
    }

    private void startMockDockerHost() throws Throwable {
        ServiceHost.Arguments args = new ServiceHost.Arguments();
        args.sandbox = null;
        args.port = 0;
        mockDockerHost = VerificationHost.create(args);
        mockDockerHost.setMaintenanceIntervalMicros(this.getMaintenanceIntervalMillis() * 1000);
        mockDockerHost.start();

        mockDockerHost.startService(Operation.createPost(UriUtils.buildUri(
                mockDockerHost, MockDockerCreateContainerService.class)),
                new MockDockerCreateContainerService());

        mockDockerCreateImageService = new MockDockerCreateImageService();
        mockDockerHost.startService(Operation.createPost(UriUtils.buildUri(
                mockDockerHost, MockDockerCreateImageService.class)),
                mockDockerCreateImageService);

        mockDockerHost.startService(Operation.createPost(UriUtils.buildUri(
                mockDockerHost, MockDockerContainerListService.class)),
                new MockDockerContainerListService());

        mockDockerHost.startService(Operation.createPost(UriUtils.buildUri(
                mockDockerHost, MockDockerCreateVolumeService.class)),
                new MockDockerCreateVolumeService());

        mockDockerHost.startService(Operation.createPost(UriUtils.buildUri(
                mockDockerHost, MockDockerVolumeListService.class)),
                new MockDockerVolumeListService());

        mockDockerHost.startService(Operation.createPost(UriUtils.buildUri(
                mockDockerHost, MockDockerInspectVolumeService.class)),
                new MockDockerInspectVolumeService());

        mockDockerHost.startService(Operation.createPost(UriUtils.buildUri(
                mockDockerHost, MockDockerNetworkService.class)),
                new MockDockerNetworkService());

    }

    public static URI getDockerUri() {
        return dockerUri;
    }

    public static URI getDockerVersionedUri() {
        return dockerVersionedUri;
    }

    public static AuthCredentialsServiceState getDockerCredentials() {
        return dockerCredentials;
    }

    public static SslTrustCertificateState getDockerServerTrust() {
        return dockerTrust;
    }

    /**
     * Is the test targeting a mock docker server or a real one?
     *
     * @return
     */
    protected boolean isMockTarget() {
        return mockDockerHost != null;
    }

    protected ComputeState requestDockerHostOperation(String mockDockerPath,
            ContainerHostOperationType operationType) throws Throwable {
        mockDockerHost.waitForServiceAvailable(MockDockerHostService.SELF_LINK + mockDockerPath);

        sendContainerHostRequest(operationType,
                UriUtils.buildUri(host, dockerHostState.documentSelfLink));

        // wait for provisioning task stage to change to finish
        waitForPropertyValue(provisioningTaskLink, MockTaskState.class, TASK_INFO_STAGE,
                TaskState.TaskStage.FINISHED);

        dockerHostState = retrieveDockerHostState();

        return dockerHostState;
    }

    protected ComputeState retrieveDockerHostState() throws Throwable {
        Operation getContainerState = Operation.createGet(UriUtils.buildUri(host,
                dockerHostState.documentSelfLink))
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        host.failIteration(ex);

                    } else {
                        dockerHostState = o.getBody(ComputeState.class);
                        host.completeIteration();
                    }
                });

        host.testStart(1);
        host.send(getContainerState);
        host.testWait();

        return dockerHostState;
    }

    protected void sendContainerHostRequest(ContainerHostOperationType type, URI computeStateReference)
            throws Throwable {
        ContainerHostRequest request = new ContainerHostRequest();
        request.resourceReference = computeStateReference;
        request.operationTypeId = type.id;
        request.serviceTaskCallback = ServiceTaskCallback.create(provisioningTaskLink);

        sendContainerHostRequest(request);
    }

    protected void sendContainerHostRequest(ContainerHostRequest request) throws Throwable {

        Operation startContainer = Operation
                .createPatch(dockerHostAdapterServiceUri)
                .setReferer(URI.create("/")).setBody(request)
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        host.failIteration(ex);
                    }

                    host.completeIteration();
                });

        host.testStart(1);
        host.send(startContainer);
        host.testWait();

        if (!isMockTarget()) {
            // in case of testing with a real docker server, give it some time to settle
            Thread.sleep(100L);
        }
    }

    protected void createTestDockerAuthCredentials() throws Throwable {
        testDockerCredentialsLink = doPost(getDockerCredentials(),
                AuthCredentialsService.FACTORY_LINK).documentSelfLink;
        SslTrustCertificateState dockerServerTrust = getDockerServerTrust();
        if (dockerServerTrust != null && dockerServerTrust.certificate != null
                && !dockerServerTrust.certificate.isEmpty()) {
            doPost(dockerServerTrust, SslTrustCertificateService.FACTORY_LINK);
        }
    }

    protected void createProvisioningTask() throws Throwable {
        MockTaskState provisioningTask = new MockTaskState();
        provisioningTaskLink = doPost(provisioningTask,
                MockTaskFactoryService.SELF_LINK).documentSelfLink;
    }

    /**
     * Get a property value from system properties or the given properties file
     *
     * @param propertyName
     * @param testProperties
     * @return
     */
    private static String getProperty(String propertyName,
            Properties testProperties) {

        String value = System.getProperty(propertyName,
                testProperties.getProperty(propertyName));

        if (value == null) {
            value = "";
        }

        // this is only needed in case someone wants to pass PEM certs/keys in
        // system properties, so need to add newlines in the string
        value = value.replaceAll("\\\\n", "\n");

        return value;
    }

}
