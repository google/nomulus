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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.util.RegistrarUtils.normalizeRegistrarId;

import com.google.api.services.admin.directory.Directory;
import com.google.api.services.groupssettings.Groupssettings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule;
import google.registry.config.CredentialModule.AdcDelegatedCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.groups.DirectoryGroupsConnection;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.UtilsModule;
import java.util.List;
import java.util.Set;
import javax.inject.Singleton;

/**
 * Verifies that the credential with the {@link AdcDelegatedCredential} annotation can be used to
 * access the Google Workspace Groups API.
 */
// TODO(b/234424397): remove class after credential changes are rolled out.
public class GroupsApiChecker {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Supplier<GroupsConnectionComponent> COMPONENT_SUPPLIER =
      Suppliers.memoize(DaggerGroupsApiChecker_GroupsConnectionComponent::create);

  public static void runGroupsApiChecks() {
    GroupsConnectionComponent component = COMPONENT_SUPPLIER.get();
    DirectoryGroupsConnection groupsConnection = component.groupsConnection();

    List<Registrar> registrars =
        Streams.stream(Registrar.loadAllCached())
            .filter(registrar -> registrar.isLive() && registrar.getType() == Registrar.Type.REAL)
            .collect(toImmutableList());
    for (Registrar registrar : registrars) {
      for (final RegistrarPoc.Type type : RegistrarPoc.Type.values()) {
        String groupKey =
            String.format(
                "%s-%s-contacts@%s",
                normalizeRegistrarId(registrar.getRegistrarId()),
                type.getDisplayName(),
                component.gSuiteDomainName());
        try {
          Set<String> currentMembers = groupsConnection.getMembersOfGroup(groupKey);
          logger.atInfo().log("Found %s members for %s.", currentMembers.size(), groupKey);
        } catch (Exception e) {
          Throwables.throwIfUnchecked(e);
          throw new RuntimeException(e);
        }
      }
    }
  }

  @Singleton
  @Component(
      modules = {
        ConfigModule.class,
        CredentialModule.class,
        GroupsApiModule.class,
        UtilsModule.class
      })
  interface GroupsConnectionComponent {
    DirectoryGroupsConnection groupsConnection();

    @Config("gSuiteDomainName")
    String gSuiteDomainName();
  }

  @Module
  static class GroupsApiModule {
    @Provides
    static Directory provideDirectory(
        @AdcDelegatedCredential GoogleCredentialsBundle credentialsBundle,
        @Config("projectId") String projectId) {
      return new Directory.Builder(
              credentialsBundle.getHttpTransport(),
              credentialsBundle.getJsonFactory(),
              credentialsBundle.getHttpRequestInitializer())
          .setApplicationName(projectId)
          .build();
    }

    @Provides
    static Groupssettings provideGroupsSettings(
        @AdcDelegatedCredential GoogleCredentialsBundle credentialsBundle,
        @Config("projectId") String projectId) {
      return new Groupssettings.Builder(
              credentialsBundle.getHttpTransport(),
              credentialsBundle.getJsonFactory(),
              credentialsBundle.getHttpRequestInitializer())
          .setApplicationName(projectId)
          .build();
    }
  }
}
