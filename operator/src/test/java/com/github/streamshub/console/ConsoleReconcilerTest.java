package com.github.streamshub.console;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.kafka.common.config.SaslConfigs;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.streamshub.console.api.v1alpha1.Console;
import com.github.streamshub.console.api.v1alpha1.ConsoleBuilder;
import com.github.streamshub.console.config.ConsoleConfig;
import com.github.streamshub.console.dependents.ConsoleSecret;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.Operator;
import io.quarkus.test.junit.QuarkusTest;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserBuilder;
import io.strimzi.api.kafka.model.user.KafkaUserScramSha512ClientAuthentication;

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

//@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class ConsoleReconcilerTest {

    private static final Logger LOGGER = Logger.getLogger(ConsoleReconcilerTest.class);
    private static final Duration LIMIT = Duration.ofSeconds(10);

    @Inject
    KubernetesClient client;

    @Inject
    Operator operator;

    Kafka kafkaCR;

    @BeforeEach
    void setUp() throws Exception {
        client.resource(Crds.kafka()).serverSideApply();
        client.resource(Crds.kafkaUser()).serverSideApply();

        var allConsoles = client.resources(Console.class).inAnyNamespace();
        var allKafkas = client.resources(Kafka.class).inAnyNamespace();
        var allKafkaUsers = client.resources(KafkaUser.class).inAnyNamespace();
        var allSecrets = client.resources(Secret.class).inAnyNamespace();

        allConsoles.delete();
        allKafkas.delete();
        allKafkaUsers.delete();
        allSecrets.delete();

        await().atMost(LIMIT).untilAsserted(() -> {
            assertTrue(allConsoles.list().getItems().isEmpty());
            assertTrue(allKafkas.list().getItems().isEmpty());
            assertTrue(allKafkaUsers.list().getItems().isEmpty());
            assertTrue(allSecrets.list().getItems().isEmpty());
        });

        operator.start();

        client.resource(new NamespaceBuilder()
                .withNewMetadata()
                    .withName("ns1")
                    .withLabels(Map.of("streamshub-operator/test", "true"))
                .endMetadata()
                .build())
            .serverSideApply();

        kafkaCR = new KafkaBuilder()
                .withNewMetadata()
                    .withName("kafka-1")
                    .withNamespace("ns1")
                .endMetadata()
                .withNewSpec()
                    .withNewKafka()
                        .addNewListener()
                            .withName("listener1")
                            .withType(KafkaListenerType.INGRESS)
                            .withPort(9093)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationScramSha512())
                        .endListener()
                    .endKafka()
                .endSpec()
                .build();

        kafkaCR = client.resource(kafkaCR).create();

        client.resource(new NamespaceBuilder()
                .withNewMetadata()
                    .withName("ns2")
                    .withLabels(Map.of("streamshub-operator/test", "true"))
                .endMetadata()
                .build())
            .serverSideApply();
    }

    @Test
    void testBasicConsoleReconciliation() {
        Console consoleCR = new ConsoleBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("console-1")
                        .withNamespace("ns2")
                        .build())
                .withNewSpec()
                    .withHostname("example.com")
                    .addNewKafkaCluster()
                        .withName(kafkaCR.getMetadata().getName())
                        .withNamespace(kafkaCR.getMetadata().getNamespace())
                        .withListener(kafkaCR.getSpec().getKafka().getListeners().get(0).getName())
                    .endKafkaCluster()
                .endSpec()
                .build();

        client.resource(consoleCR).create();

        await().ignoreException(NullPointerException.class).atMost(LIMIT).untilAsserted(() -> {
            var console = client.resources(Console.class)
                    .inNamespace(consoleCR.getMetadata().getNamespace())
                    .withName(consoleCR.getMetadata().getName())
                    .get();
            assertEquals(1, console.getStatus().getConditions().size());
            var condition = console.getStatus().getConditions().get(0);
            assertEquals("Ready", condition.getType());
            assertEquals("False", condition.getStatus());
            assertEquals("DependentsNotReady", condition.getReason());
            assertTrue(condition.getMessage().contains("ConsoleIngress"));
            assertTrue(condition.getMessage().contains("PrometheusDeployment"));
        });

        client.apps().deployments()
            .inNamespace(consoleCR.getMetadata().getNamespace())
            .withName("console-1-prometheus-deployment")
            .editStatus(this::setReady);
        LOGGER.info("Set ready replicas for Prometheus deployment");

        var consoleIngress = client.network().v1().ingresses()
            .inNamespace(consoleCR.getMetadata().getNamespace())
            .withName("console-1-console-ingress")
            .get();

        consoleIngress = consoleIngress.edit()
                    .editOrNewStatus()
                        .withNewLoadBalancer()
                            .addNewIngress()
                                .withHostname("ingress.example.com")
                            .endIngress()
                        .endLoadBalancer()
                    .endStatus()
                    .build();
        client.resource(consoleIngress).patchStatus();
        LOGGER.info("Set ingress status for Console ingress");

        await().ignoreException(NullPointerException.class).atMost(LIMIT).untilAsserted(() -> {
            var console = client.resources(Console.class)
                    .inNamespace(consoleCR.getMetadata().getNamespace())
                    .withName(consoleCR.getMetadata().getName())
                    .get();
            assertEquals(1, console.getStatus().getConditions().size());
            var condition = console.getStatus().getConditions().get(0);
            assertEquals("Ready", condition.getType());
            assertEquals("False", condition.getStatus());
            assertEquals("DependentsNotReady", condition.getReason());
            assertTrue(condition.getMessage().contains("ConsoleDeployment"));
        });

        client.apps().deployments()
                .inNamespace(consoleCR.getMetadata().getNamespace())
                .withName("console-1-console-deployment")
                .editStatus(this::setReady);
        LOGGER.info("Set ready replicas for Console deployment");

        await().ignoreException(NullPointerException.class).atMost(LIMIT).untilAsserted(() -> {
            var console = client.resources(Console.class)
                    .inNamespace(consoleCR.getMetadata().getNamespace())
                    .withName(consoleCR.getMetadata().getName())
                    .get();
            assertEquals(1, console.getStatus().getConditions().size());
            var condition = console.getStatus().getConditions().get(0);
            assertEquals("Ready", condition.getType());
            assertEquals("True", condition.getStatus());
            assertNull(condition.getReason());
            assertEquals("All resources ready", condition.getMessage());
        });
    }

    @Test
    void testConsoleReconciliationWithInvalidListenerName() {
        Console consoleCR = new ConsoleBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("console-1")
                        .withNamespace("ns2")
                        .build())
                .withNewSpec()
                    .withHostname("example.com")
                    .addNewKafkaCluster()
                        .withName(kafkaCR.getMetadata().getName())
                        .withNamespace(kafkaCR.getMetadata().getNamespace())
                        .withListener("invalid")
                    .endKafkaCluster()
                .endSpec()
                .build();

        client.resource(consoleCR).create();

        await().ignoreException(NullPointerException.class).atMost(LIMIT).untilAsserted(() -> {
            var console = client.resources(Console.class)
                    .inNamespace(consoleCR.getMetadata().getNamespace())
                    .withName(consoleCR.getMetadata().getName())
                    .get();
            assertEquals(2, console.getStatus().getConditions().size());
            var ready = console.getStatus().getConditions().get(0);
            assertEquals("Ready", ready.getType());
            assertEquals("False", ready.getStatus());
            assertEquals("DependentsNotReady", ready.getReason());
            var warning = console.getStatus().getConditions().get(1);
            assertEquals("Warning", warning.getType());
            assertEquals("True", warning.getStatus());
            assertEquals("ReconcileException", warning.getReason());
        });
    }

    @Test
    void testConsoleReconciliationWithMissingKafkaUser() {
        Console consoleCR = new ConsoleBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("console-1")
                        .withNamespace("ns2")
                        .build())
                .withNewSpec()
                    .withHostname("example.com")
                    .addNewKafkaCluster()
                        .withName(kafkaCR.getMetadata().getName())
                        .withNamespace(kafkaCR.getMetadata().getNamespace())
                        .withListener(kafkaCR.getSpec().getKafka().getListeners().get(0).getName())
                        .withNewCredentials()
                            .withNewKafkaUser()
                                .withName("invalid")
                            .endKafkaUser()
                        .endCredentials()
                    .endKafkaCluster()
                .endSpec()
                .build();

        client.resource(consoleCR).create();

        await().ignoreException(NullPointerException.class).atMost(LIMIT).untilAsserted(() -> {
            var console = client.resources(Console.class)
                    .inNamespace(consoleCR.getMetadata().getNamespace())
                    .withName(consoleCR.getMetadata().getName())
                    .get();
            assertEquals(2, console.getStatus().getConditions().size());
            var ready = console.getStatus().getConditions().get(0);
            assertEquals("Ready", ready.getType());
            assertEquals("False", ready.getStatus());
            assertEquals("DependentsNotReady", ready.getReason());
            var warning = console.getStatus().getConditions().get(1);
            assertEquals("Warning", warning.getType());
            assertEquals("True", warning.getStatus());
            assertEquals("ReconcileException", warning.getReason());
            assertEquals("No such KafkaUser resource: ns1/invalid", warning.getMessage());
        });
    }

    @Test
    void testConsoleReconciliationWithMissingKafkaUserStatus() {
        KafkaUser userCR = new KafkaUserBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("ku1")
                        .withNamespace("ns1")
                        .build())
                .withNewSpec()
                    .withAuthentication(new KafkaUserScramSha512ClientAuthentication())
                .endSpec()
                // no status
                .build();

        client.resource(userCR).create();

        Console consoleCR = new ConsoleBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("console-1")
                        .withNamespace("ns2")
                        .build())
                .withNewSpec()
                    .withHostname("example.com")
                    .addNewKafkaCluster()
                        .withName(kafkaCR.getMetadata().getName())
                        .withNamespace(kafkaCR.getMetadata().getNamespace())
                        .withListener(kafkaCR.getSpec().getKafka().getListeners().get(0).getName())
                        .withNewCredentials()
                            .withNewKafkaUser()
                                .withName(userCR.getMetadata().getName())
                            .endKafkaUser()
                        .endCredentials()
                    .endKafkaCluster()
                .endSpec()
                .build();

        client.resource(consoleCR).create();

        await().ignoreException(NullPointerException.class).atMost(LIMIT).untilAsserted(() -> {
            var console = client.resources(Console.class)
                    .inNamespace(consoleCR.getMetadata().getNamespace())
                    .withName(consoleCR.getMetadata().getName())
                    .get();
            assertEquals(2, console.getStatus().getConditions().size());
            var ready = console.getStatus().getConditions().get(0);
            assertEquals("Ready", ready.getType());
            assertEquals("False", ready.getStatus());
            assertEquals("DependentsNotReady", ready.getReason());
            var warning = console.getStatus().getConditions().get(1);
            assertEquals("Warning", warning.getType());
            assertEquals("True", warning.getStatus());
            assertEquals("ReconcileException", warning.getReason());
            assertEquals("KafkaUser ns1/ku1 missing .status.secret", warning.getMessage());
        });
    }

    @Test
    void testConsoleReconciliationWithMissingJaasConfigKey() {
        KafkaUser userCR = new KafkaUserBuilder()
                .withNewMetadata()
                    .withName("ku1")
                    .withNamespace("ns1")
                .endMetadata()
                .withNewSpec()
                    .withAuthentication(new KafkaUserScramSha512ClientAuthentication())
                .endSpec()
                .build();

        userCR = client.resource(userCR).create();
        client.resource(userCR).editStatus(user -> new KafkaUserBuilder(user)
                .withNewStatus()
                    .withSecret("ku1")
                .endStatus()
                .build());

        Secret userSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("ku1")
                    .withNamespace("ns1")
                .endMetadata()
                // no data map
                .build();

        client.resource(userSecret).create();

        Console consoleCR = new ConsoleBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("console-1")
                        .withNamespace("ns2")
                        .build())
                .withNewSpec()
                    .withHostname("example.com")
                    .addNewKafkaCluster()
                        .withName(kafkaCR.getMetadata().getName())
                        .withNamespace(kafkaCR.getMetadata().getNamespace())
                        .withListener(kafkaCR.getSpec().getKafka().getListeners().get(0).getName())
                        .withNewCredentials()
                            .withNewKafkaUser()
                                .withName(userCR.getMetadata().getName())
                            .endKafkaUser()
                        .endCredentials()
                    .endKafkaCluster()
                .endSpec()
                .build();

        client.resource(consoleCR).create();

        await().ignoreException(NullPointerException.class).atMost(LIMIT).untilAsserted(() -> {
            var console = client.resources(Console.class)
                    .inNamespace(consoleCR.getMetadata().getNamespace())
                    .withName(consoleCR.getMetadata().getName())
                    .get();
            assertEquals(2, console.getStatus().getConditions().size());
            var ready = console.getStatus().getConditions().get(0);
            assertEquals("Ready", ready.getType());
            assertEquals("False", ready.getStatus());
            assertEquals("DependentsNotReady", ready.getReason());
            var warning = console.getStatus().getConditions().get(1);
            assertEquals("Warning", warning.getType());
            assertEquals("True", warning.getStatus());
            assertEquals("ReconcileException", warning.getReason());
            assertEquals("Secret ns1/ku1 missing key 'sasl.jaas.config'", warning.getMessage());
        });
    }

    @Test
    void testConsoleReconciliationWithValidKafkaUser() {
        KafkaUser userCR = new KafkaUserBuilder()
                .withNewMetadata()
                    .withName("ku1")
                    .withNamespace("ns1")
                .endMetadata()
                .withNewSpec()
                    .withAuthentication(new KafkaUserScramSha512ClientAuthentication())
                .endSpec()
                .build();

        userCR = client.resource(userCR).create();
        client.resource(userCR).editStatus(user -> new KafkaUserBuilder(user)
                .withNewStatus()
                    .withSecret("ku1")
                .endStatus()
                .build());

        Secret userSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName("ku1")
                    .withNamespace("ns1")
                .endMetadata()
                .addToData(SaslConfigs.SASL_JAAS_CONFIG, Base64.getEncoder().encodeToString("jaas-config-value".getBytes()))
                .build();

        client.resource(userSecret).create();

        Console consoleCR = new ConsoleBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("console-1")
                        .withNamespace("ns2")
                        .build())
                .withNewSpec()
                    .withHostname("example.com")
                    .addNewKafkaCluster()
                        .withName(kafkaCR.getMetadata().getName())
                        .withNamespace(kafkaCR.getMetadata().getNamespace())
                        .withListener(kafkaCR.getSpec().getKafka().getListeners().get(0).getName())
                        .withNewCredentials()
                            .withNewKafkaUser()
                                .withName(userCR.getMetadata().getName())
                            .endKafkaUser()
                        .endCredentials()
                    .endKafkaCluster()
                .endSpec()
                .build();

        client.resource(consoleCR).create();

        await().ignoreException(NullPointerException.class).atMost(LIMIT).untilAsserted(() -> {
            var console = client.resources(Console.class)
                    .inNamespace(consoleCR.getMetadata().getNamespace())
                    .withName(consoleCR.getMetadata().getName())
                    .get();
            assertEquals(1, console.getStatus().getConditions().size());
            var ready = console.getStatus().getConditions().get(0);
            assertEquals("Ready", ready.getType());
            assertEquals("False", ready.getStatus());
            assertEquals("DependentsNotReady", ready.getReason());

            var consoleSecret = client.secrets().inNamespace("ns2").withName("console-1-" + ConsoleSecret.NAME).get();
            assertNotNull(consoleSecret);
            String configEncoded = consoleSecret.getData().get("console-config.yaml");
            byte[] configDecoded = Base64.getDecoder().decode(configEncoded);
            ConsoleConfig config = new ObjectMapper().readValue(configDecoded, ConsoleConfig.class);
            assertEquals("jaas-config-value",
                    config.getKafka().getClusters().get(0).getProperties().get(SaslConfigs.SASL_JAAS_CONFIG));
        });
    }

    // Utility

    private Deployment setReady(Deployment deployment) {
        int desiredReplicas = Optional.ofNullable(deployment.getSpec().getReplicas()).orElse(1);

        return deployment.edit()
            .editOrNewStatus()
                .withReplicas(desiredReplicas)
                .withReadyReplicas(desiredReplicas)
            .endStatus()
            .build();
    }
}
