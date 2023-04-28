// Copyright 2022 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.batch.cannedscript;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dns.Dns;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.CloudTasksSettings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.flogger.FluentLogger;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.UtilsModule;
import java.io.IOException;
import java.io.Serializable;
import java.util.Optional;
import javax.inject.Singleton;

/** Canned actions invoked from {@link google.registry.batch.CannedScriptExecutionAction}. */
// TODO(b/277239043): remove class after credential changes are rolled out.
public class CannedScripts {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Supplier<CannedScriptsComponent> COMPONENT_SUPPLIER =
      Suppliers.memoize(DaggerCannedScripts_CannedScriptsComponent::create);

  public static void runAllChecks() {
    CannedScriptsComponent component = COMPONENT_SUPPLIER.get();
    String projectId = component.projectId();
    Bigquery bigquery = component.bigQuery();
    try {
      bigquery.datasets().list(projectId).execute().getDatasets().stream()
          .findAny()
          .ifPresent(
              datasets ->
                  logger.atInfo().log("Found a BQ dataset [%s]", datasets.getFriendlyName()));
      logger.atInfo().log("Finished accessing BQ.");
    } catch (IOException ioe) {
      logger.atSevere().withCause(ioe).log("Failed to access bigquery.");
    }
    try {
      Dataflow dataflow = component.dataflow();
      dataflow.projects().jobs().list(projectId).execute().getJobs().stream()
          .findAny()
          .ifPresent(job -> logger.atInfo().log("Found a job [%s]", job.getName()));
      logger.atInfo().log("Finished accessing Dataflow.");
    } catch (IOException ioe) {
      logger.atSevere().withCause(ioe).log("Failed to access dataflow.");
    }
    try {
      Storage gcs = component.gcs();
      gcs.listAcls(projectId + "-beam");
      logger.atInfo().log("Finished accessing gcs.");
    } catch (RuntimeException e) {
      logger.atSevere().withCause(e).log("Failed to access gcs.");
    }
    try {
      Dns dns = component.dns();
      dns.managedZones().list(projectId).execute().getManagedZones().stream()
          .findAny()
          .ifPresent(zone -> logger.atInfo().log("Found one zone [%s].", zone.getName()));
      logger.atInfo().log("Finished accessing dns.");
    } catch (IOException ioe) {
      logger.atSevere().withCause(ioe).log("Failed to access dns.");
    }
    try {
      CloudTasksClient client = component.cloudtasksClient().get();
      com.google.cloud.tasks.v2.Queue queue =
          client.getQueue(
              String.format(
                  "projects/%s/locations/%s/queues/async-actions",
                  projectId, component.locationId()));
      logger.atInfo().log("Got async queue state [%s]", queue.getState().name());
      logger.atInfo().log("Finished accessing cloudtasks.");
    } catch (RuntimeException e) {
      logger.atSevere().withCause(e).log("Failed to access cloudtasks.");
    }
  }

  @Singleton
  @Component(
      modules = {
        ConfigModule.class,
        CredentialModule.class,
        CannedScriptsModule.class,
        UtilsModule.class
      })
  interface CannedScriptsComponent {
    Bigquery bigQuery();

    java.util.function.Supplier<CloudTasksClient> cloudtasksClient();

    Dataflow dataflow();

    Dns dns();

    Storage gcs();

    @Config("projectId")
    String projectId();

    @Config("locationId")
    String locationId();
  }

  @Module
  static class CannedScriptsModule {
    @Provides
    static Bigquery provideBigquery(
        @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
        @Config("projectId") String projectId) {
      return new Bigquery.Builder(
              credentialsBundle.getHttpTransport(),
              credentialsBundle.getJsonFactory(),
              credentialsBundle.getHttpRequestInitializer())
          .setApplicationName(projectId)
          .build();
    }

    @Provides
    static Dataflow provideDataflow(
        @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
        @Config("projectId") String projectId) {
      return new Dataflow.Builder(
              credentialsBundle.getHttpTransport(),
              credentialsBundle.getJsonFactory(),
              credentialsBundle.getHttpRequestInitializer())
          .setApplicationName(String.format("%s billing", projectId))
          .build();
    }

    @Provides
    static Storage provideGcs(
        @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle) {
      return StorageOptions.newBuilder()
          .setCredentials(credentialsBundle.getGoogleCredentials())
          .build()
          .getService();
    }

    @Provides
    static Dns provideDns(
        @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
        @Config("projectId") String projectId,
        @Config("cloudDnsRootUrl") Optional<String> rootUrl,
        @Config("cloudDnsServicePath") Optional<String> servicePath) {
      Dns.Builder builder =
          new Dns.Builder(
                  credentialsBundle.getHttpTransport(),
                  credentialsBundle.getJsonFactory(),
                  credentialsBundle.getHttpRequestInitializer())
              .setApplicationName(projectId);

      rootUrl.ifPresent(builder::setRootUrl);
      servicePath.ifPresent(builder::setServicePath);

      return builder.build();
    }

    @Provides
    public static java.util.function.Supplier<CloudTasksClient> provideCloudTasksClientSupplier(
        @ApplicationDefaultCredential GoogleCredentialsBundle credentials) {
      return (java.util.function.Supplier<CloudTasksClient> & Serializable)
          () -> {
            CloudTasksClient client;
            try {
              client =
                  CloudTasksClient.create(
                      CloudTasksSettings.newBuilder()
                          .setCredentialsProvider(
                              FixedCredentialsProvider.create(credentials.getGoogleCredentials()))
                          .build());
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
            return client;
          };
    }
  }
}
