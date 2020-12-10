// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.privileges.secretmanager;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.api.gax.rpc.ApiException;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Replication.Automatic;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient.ListSecretVersionsPagedResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient.ListSecretsPagedResponse;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersion;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import com.google.common.collect.Streams;
import com.google.protobuf.ByteString;
import google.registry.util.Retrier;
import java.util.Optional;
import java.util.concurrent.Callable;

/** Implements {@link SecretManagerClient} on Google Cloud Platform. */
public class SecretManagerClientImpl implements SecretManagerClient {
  private final String project;
  private final SecretManagerServiceClient csmClient;
  private final Retrier retrier;

  SecretManagerClientImpl(String project, SecretManagerServiceClient csmClient, Retrier retrier) {
    this.project = project;
    this.csmClient = csmClient;
    this.retrier = retrier;
  }

  @Override
  public String getProject() {
    return project;
  }

  @Override
  public void createSecret(String secretId) {
    checkNotNull(secretId, "secretId");
    Secret secretSettings = Secret.newBuilder().setReplication(defaultReplicationPolicy()).build();
    callSecretManager(
        () -> csmClient.createSecret(ProjectName.of(project), secretId, secretSettings));
  }

  @Override
  public boolean secretExists(String secretId) {
    checkNotNull(secretId, "secretId");
    try {
      callSecretManager(() -> csmClient.getSecret(SecretName.of(project, secretId)));
      return true;
    } catch (NoSuchSecretResourceException e) {
      return false;
    }
  }

  @Override
  public Iterable<String> listSecrets() {
    ListSecretsPagedResponse response =
        callSecretManager(() -> csmClient.listSecrets(ProjectName.of(project)));
    return () ->
        Streams.stream(response.iterateAll())
            .map(secret -> SecretName.parse(secret.getName()).getSecret())
            .iterator();
  }

  @Override
  public Iterable<SecretVersionState> listSecretVersions(String secretId) {
    checkNotNull(secretId, "secretId");
    ListSecretVersionsPagedResponse response =
        callSecretManager(() -> csmClient.listSecretVersions(SecretName.of(project, secretId)));
    return () ->
        Streams.stream(response.iterateAll())
            .map(SecretManagerClientImpl::toSecretVersionState)
            .iterator();
  }

  private static SecretVersionState toSecretVersionState(SecretVersion secretVersion) {
    SecretVersionName name = SecretVersionName.parse(secretVersion.getName());
    return SecretVersionState.of(
        name.getSecret(), name.getSecretVersion(), secretVersion.getState());
  }

  @Override
  public String addSecretVersion(String secretId, String data) {
    checkNotNull(secretId, "secretId");
    checkNotNull(data, "data");
    SecretName secretName = SecretName.of(project, secretId);
    SecretPayload secretPayload =
        SecretPayload.newBuilder().setData(ByteString.copyFromUtf8(data)).build();
    SecretVersion response =
        callSecretManager(() -> csmClient.addSecretVersion(secretName, secretPayload));
    checkState(SecretVersionName.isParsableFrom(response.getName()));
    SecretVersionName secretVersionName = SecretVersionName.parse(response.getName());
    return secretVersionName.getSecretVersion();
  }

  @Override
  public String getSecretData(String secretId, Optional<String> version) {
    checkNotNull(secretId, "secretId");
    checkNotNull(version, "version");
    return callSecretManager(
        () ->
            csmClient
                .accessSecretVersion(
                    SecretVersionName.of(project, secretId, version.orElse("latest")))
                .getPayload()
                .getData()
                .toStringUtf8());
  }

  @Override
  public void enableSecretVersion(String secretId, String version) {
    checkNotNull(secretId, "secretId");
    checkNotNull(version, "version");
    callSecretManager(
        () -> csmClient.enableSecretVersion(SecretVersionName.of(project, secretId, version)));
  }

  @Override
  public void disableSecretVersion(String secretId, String version) {
    checkNotNull(secretId, "secretId");
    checkNotNull(version, "version");
    callSecretManager(
        () -> csmClient.disableSecretVersion(SecretVersionName.of(project, secretId, version)));
  }

  @Override
  public void destroySecretVersion(String secretId, String version) {
    checkNotNull(secretId, "secretId");
    checkNotNull(version, "version");
    callSecretManager(
        () -> csmClient.destroySecretVersion(SecretVersionName.of(project, secretId, version)));
  }

  @Override
  public void deleteSecret(String secretId) {
    checkNotNull(secretId, "secretId");
    callSecretManager(
        () -> {
          csmClient.deleteSecret(SecretName.of(project, secretId));
          return null;
        });
  }

  private <T> T callSecretManager(Callable<T> callable) {
    try {
      return retrier.callWithRetry(callable, SecretManagerClientImpl::isRetryableException);
    } catch (ApiException e) {
      switch (e.getStatusCode().getCode()) {
        case ALREADY_EXISTS:
          throw new SecretAlreadyExistsException(e);
        case NOT_FOUND:
          throw new NoSuchSecretResourceException(e);
        default:
          throw new SecretManagerException(e);
      }
    }
  }

  private static boolean isRetryableException(Throwable e) {
    return e instanceof ApiException && ((ApiException) e).isRetryable();
  }

  private static Replication defaultReplicationPolicy() {
    return Replication.newBuilder().setAutomatic(Automatic.newBuilder().build()).build();
  }
}
