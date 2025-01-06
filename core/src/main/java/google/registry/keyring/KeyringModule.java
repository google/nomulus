// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.keyring;

import static com.google.common.base.Preconditions.checkState;

import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.Keyring;
import java.util.Map;
import java.util.Optional;
import javax.inject.Singleton;

/** Dagger module for {@link Keyring} */
@Module
public final class KeyringModule {

  @Provides
  @Singleton
  public static Keyring provideKeyring(
      Map<String, Keyring> keyrings, @Config("activeKeyring") String activeKeyring) {
    checkState(
        keyrings.containsKey(activeKeyring),
        "Invalid Keyring %s is configured; valid choices are %s",
        activeKeyring,
        keyrings.keySet());
    return keyrings.get(activeKeyring);
  }

  @Provides
  @Config("cloudSqlInstanceConnectionName")
  public static String provideCloudSqlInstanceConnectionName(Keyring keyring) {
    return keyring.getSqlPrimaryConnectionName();
  }

  @Provides
  @Config("cloudSqlReplicaInstanceConnectionName")
  public static Optional<String> provideCloudSqlReplicaInstanceConnectionName(Keyring keyring) {
    return Optional.ofNullable(keyring.getSqlReplicaConnectionName());
  }

  @Provides
  @Config("cloudSqlDbInstanceName")
  public static String provideCloudSqlDbInstance(
      @Config("cloudSqlInstanceConnectionName") String instanceConnectionName) {
    // Format of instanceConnectionName: project-id:region:instance-name
    int lastColonIndex = instanceConnectionName.lastIndexOf(':');
    return instanceConnectionName.substring(lastColonIndex + 1);
  }
}
