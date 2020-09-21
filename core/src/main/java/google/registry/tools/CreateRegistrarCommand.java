// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.collect.Iterables.getOnlyElement;
import static google.registry.model.registrar.Registrar.State.ACTIVE;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.tools.RegistryToolEnvironment.PRODUCTION;
import static google.registry.tools.RegistryToolEnvironment.SANDBOX;
import static google.registry.tools.RegistryToolEnvironment.UNITTEST;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static google.registry.util.RegistrarUtils.normalizeClientId;
import static java.util.stream.Collectors.toCollection;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import google.registry.config.RegistryEnvironment;
import google.registry.model.registrar.Registrar;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/** Command to create a Registrar. */
@Parameters(separators = " =", commandDescription = "Create new registrar account(s)")
final class CreateRegistrarCommand extends CreateOrUpdateRegistrarCommand
    implements CommandWithConnection, CommandWithRemoteApi {

  private static final ImmutableSet<RegistryToolEnvironment> ENVIRONMENTS_ALLOWING_GROUP_CREATION =
      ImmutableSet.of(PRODUCTION, SANDBOX, UNITTEST);

  @Parameter(
      names = "--create_groups",
      description = "Whether the Google Groups for this registrar should be created",
      arity = 1)
  boolean createGoogleGroups = true;

  private AppEngineConnection connection;

  @Override
  public void setConnection(AppEngineConnection connection) {
    this.connection = connection;
  }

  @Override
  protected void initRegistrarCommand() {
    checkArgument(mainParameters.size() == 1, "Must specify exactly one client identifier.");
    checkArgumentNotNull(emptyToNull(password), "--password is a required field");
    checkArgumentNotNull(registrarName, "--name is a required field");
    checkArgumentNotNull(icannReferralEmail, "--icann_referral_email is a required field");
    checkArgumentNotNull(street, "Address fields are required when creating a registrar");
    // Default new registrars to active.
    registrarState = Optional.ofNullable(registrarState).orElse(ACTIVE);
  }

  @Override
  void saveToCloudSql(Registrar registrar) {
    jpaTm().insert(registrar);
  }

  @Nullable
  @Override
  Registrar getOldRegistrar(final String clientId) {
    checkArgument(clientId.length() >= 3, "Client identifier (%s) is too short", clientId);
    checkArgument(clientId.length() <= 16, "Client identifier (%s) is too long", clientId);
    if (Registrar.Type.REAL.equals(registrarType)) {
      checkArgument(
          clientId.equals(normalizeClientId(clientId)),
          "Client identifier (%s) can only contain lowercase letters, numbers, and hyphens",
          clientId);
    }
    checkState(
        !Registrar.loadByClientId(clientId).isPresent(), "Registrar %s already exists", clientId);
    List<Registrar> collisions =
        Streams.stream(Registrar.loadAll())
            .filter(registrar -> normalizeClientId(registrar.getClientId()).equals(clientId))
            .collect(toCollection(ArrayList::new));
    if (!collisions.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "The registrar client identifier %s normalizes identically to existing registrar %s",
              clientId, collisions.get(0).getClientId()));
    }
    return null;
  }

  @Override
  void checkModifyAllowedTlds(@Nullable Registrar oldRegistrar) {
    // When creating a registrar, only allow allowed-TLD modification if we're in a non-PRODUCTION
    // environment and/or the registrar is not REAL
    checkArgument(
        !RegistryEnvironment.PRODUCTION.equals(RegistryEnvironment.get())
            || !Registrar.Type.REAL.equals(registrarType),
        "Cannot add allowed TLDs when creating a REAL registrar in a production environment."
            + " Please create the registrar without allowed TLDs, then use `nomulus"
            + " registrar_contact` to create a registrar contact for it that is visible as the"
            + " abuse contact in WHOIS. Then use `nomulus update_registrar` to add the allowed"
            + " TLDs.");
  }

  @Override
  protected String postExecute() {
    if (!createGoogleGroups) {
      return "";
    }
    // Allow prod and sandbox because they actually have Groups, and UNITTEST for testing.
    if (!ENVIRONMENTS_ALLOWING_GROUP_CREATION.contains(RegistryToolEnvironment.get())) {
      return "\nSkipping registrar groups creation because only production and sandbox support it.";
    }
    try {
      // We know it is safe to use the only main parameter here because initRegistrarCommand has
      // already verified that there is only one, and getOldRegistrar has already verified that a
      // registrar with this clientId doesn't already exist.
      CreateRegistrarGroupsCommand.executeOnServer(connection, getOnlyElement(mainParameters));
    } catch (Exception e) {
      return "\nRegistrar created, but groups creation failed with error:\n" + e;
    }
    return "\nRegistrar groups created successfully.";
  }
}
