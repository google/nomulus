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

import com.google.cloud.secretmanager.v1.SecretVersion.State;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import javax.inject.Inject;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

/** Implements {@link SecretManagerClient} for tests. */
public class FakeSecretManagerClient implements SecretManagerClient {

  private final HashMap<String, SecretEntry> secrets = new HashMap<>();

  @Inject
  FakeSecretManagerClient() {}

  @Override
  public void createSecret(String secretId) {
    checkNotNull(secretId, "secretId");
    if (secrets.containsKey(secretId)) {
      throw new SecretAlreadyExistsException(null);
    }
    secrets.put(secretId, new SecretEntry(secretId));
  }

  @Override
  public Iterable<String> listSecrets() {
    return ImmutableSet.copyOf(secrets.keySet());
  }

  @Override
  public Iterable<SecretVersionState> listSecretVersions(String secretId) {
    checkNotNull(secretId, "secretId");
    SecretEntry secretEntry = secrets.get(secretId);
    if (secretEntry == null) {
      throw new NoSuchSecretResourceException(null);
    }
    return secretEntry.listVersions();
  }

  @Override
  public String addSecretVersion(String secretId, String data) {
    checkNotNull(secretId, "secretId");
    checkNotNull(data, "data");
    SecretEntry secretEntry = secrets.get(secretId);
    if (secretEntry == null) {
      throw new NoSuchSecretResourceException(null);
    }
    return secretEntry.addVersion(data);
  }

  @Override
  public String getSecretData(String secretId, Optional<String> version) {
    checkNotNull(secretId, "secretId");
    checkNotNull(version, "version");
    SecretEntry secretEntry = secrets.get(secretId);
    if (secretEntry == null) {
      throw new NoSuchSecretResourceException(null);
    }
    return secretEntry.getVersion(version).getData();
  }

  @Override
  public void destroySecretVersion(String secretId, String version) {
    checkNotNull(secretId, "secretId");
    checkNotNull(version, "version");
    SecretEntry secretEntry = secrets.get(secretId);
    if (secretEntry == null) {
      throw new NoSuchSecretResourceException(null);
    }
    secretEntry.destroyVersion(version);
  }

  @Override
  public void deleteSecret(String secretId) {
    checkNotNull(secretId, "secretId");
    if (!secrets.containsKey(secretId)) {
      throw new NoSuchSecretResourceException(null);
    }
    secrets.remove(secretId);
  }

  private static class VersionEntry {
    private String data;
    private State state;

    VersionEntry(String data) {
      this.data = checkNotNull(data, "data");
      this.state = State.ENABLED;
    }

    String getData() {
      if (state != State.ENABLED) {
        throw new SecretManagerException(null);
      }
      return data;
    }

    State getState() {
      return state;
    }

    void destroy() {
      data = null;
      state = State.DESTROYED;
    }
  }

  private static class SecretEntry {
    private final String secretId;
    private ArrayList<VersionEntry> versions;

    SecretEntry(String secretId) {
      this.secretId = secretId;
      versions = new ArrayList<>();
    }

    String addVersion(String data) {
      VersionEntry versionEntry = new VersionEntry(data);
      versions.add(versionEntry);
      return String.valueOf(versions.size() - 1);
    }

    VersionEntry getVersion(Optional<String> version) {
      try {
        int index = version.map(Integer::valueOf).orElse(versions.size() - 1);
        return versions.get(index);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid version " + version.get());
      }
    }

    Iterable<SecretVersionState> listVersions() {
      ImmutableList.Builder<SecretVersionState> builder = new ImmutableList.Builder<>();
      for (int i = 0; i < versions.size(); i++) {
        builder.add(SecretVersionState.of(secretId, String.valueOf(i), versions.get(i).getState()));
      }
      return builder.build();
    }

    void destroyVersion(String version) {
      try {
        int index = Integer.valueOf(version);
        versions.get(index).destroy();
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid version " + version);
      } catch (ArrayIndexOutOfBoundsException e) {
        throw new NoSuchSecretResourceException(null);
      }
    }
  }
}
