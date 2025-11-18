// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.RegistryEnvironment.PRODUCTION;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.gson.annotations.Expose;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.OteAccountBuilder;
import google.registry.model.OteStats;
import google.registry.model.OteStats.StatType;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.tools.IamClient;
import google.registry.util.RegistryEnvironment;
import google.registry.util.StringGenerator;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Map;
import java.util.Optional;

@Action(
    service = Service.CONSOLE,
    path = ConsoleOteAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleOteAction extends ConsoleApiAction {
  static final String PATH = "/console-api/ote";
  private static final int PASSWORD_LENGTH = 16;
  private static final String COMPLETED_PARAM = "completed";
  private static final String STAT_TYPE_DESCRIPTION_PARAM = "description";
  private static final String STAT_TYPE_REQUIREMENT_PARAM = "requirement";
  private static final String STAT_TYPE_TIMES_PERFORMED_PARAM = "timesPerformed";
  private final StringGenerator passwordGenerator;
  private final Optional<OteCreateData> oteCreateData;
  private final Optional<String> maybeGroupEmailAddress;
  private final IamClient iamClient;
  private final String registrarId;

  @Inject
  public ConsoleOteAction(
      ConsoleApiParams consoleApiParams,
      IamClient iamClient,
      @Parameter("registrarId") String registrarId, // Get request param
      @Config("gSuiteConsoleUserGroupEmailAddress") Optional<String> maybeGroupEmailAddress,
      @Named("base58StringGenerator") StringGenerator passwordGenerator,
      @Parameter("oteCreateData") Optional<OteCreateData> oteCreateData) {
    super(consoleApiParams);
    this.passwordGenerator = passwordGenerator;
    this.oteCreateData = oteCreateData;
    this.maybeGroupEmailAddress = maybeGroupEmailAddress;
    this.iamClient = iamClient;
    this.registrarId = registrarId;
  }

  @Override
  protected void postHandler(User user) {
    checkState(!RegistryEnvironment.get().equals(PRODUCTION), "Can't create OT&E in prod");

    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.EDIT_REGISTRAR_DETAILS)) {
      setFailedResponse("User doesn't have a permission to create OT&E accounts", SC_FORBIDDEN);
      return;
    }

    boolean isBodyValid =
        this.oteCreateData.isPresent()
            && !this.oteCreateData.get().registrarId.isEmpty()
            && !this.oteCreateData.get().registrarEmail.isEmpty();

    checkArgument(isBodyValid, "OT&E create body is invalid");

    String password = passwordGenerator.createString(PASSWORD_LENGTH);

    OteAccountBuilder oteAccountBuilder =
        OteAccountBuilder.forRegistrarId(this.oteCreateData.get().registrarId)
            .addUser(this.oteCreateData.get().registrarEmail)
            .setPassword(password);

    ImmutableMap<String, String> registrarIdToTld = oteAccountBuilder.buildAndPersist();
    oteAccountBuilder.grantIapPermission(maybeGroupEmailAddress, cloudTasksUtils, iamClient);
    consoleApiParams.response().setStatus(SC_OK);
    consoleApiParams
        .response()
        .setPayload(
            consoleApiParams
                .gson()
                .toJson(
                    ImmutableMap.builder()
                        .putAll(registrarIdToTld)
                        .put("password", password)
                        .build()));
  }

  @Override
  protected void getHandler(User user) {
    checkArgument(!Strings.isNullOrEmpty(registrarId), "Missing registrarId parameter");

    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.EDIT_REGISTRAR_DETAILS)) {
      setFailedResponse("User doesn't have a permission to check OT&E status", SC_BAD_REQUEST);
      return;
    }

    String baseRegistrarId = OteAccountBuilder.getBaseRegistrarId(registrarId);
    tm().transact(
            () -> {
              Optional<Registrar> registrar = Registrar.loadByRegistrarId(registrarId);
              if (registrar.isEmpty()) {
                setFailedResponse(
                    String.format("Registrar with ID %s is not present", registrarId),
                    SC_BAD_REQUEST);
                return;
              }
              if (!Registrar.Type.OTE.equals(registrar.get().getType())) {
                setFailedResponse(
                    String.format("Registrar with ID %s is not an OT&E registrar", registrarId),
                    SC_BAD_REQUEST);
                return;
              }
              OteStats oteStats = OteStats.getFromRegistrar(baseRegistrarId);
              var stats =
                  StatType.REQUIRED_STAT_TYPES.stream()
                      .map(
                          statType ->
                              convertSingleRequirement(statType, oteStats.getCount(statType)))
                      .collect(toImmutableList());
              consoleApiParams.response().setStatus(SC_OK);
              consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(stats));
            });
  }

  private Map<String, Object> convertSingleRequirement(StatType statType, int count) {
    int requirement = statType.getRequirement();
    return ImmutableMap.of(
        STAT_TYPE_DESCRIPTION_PARAM,
        statType.getDescription(),
        STAT_TYPE_REQUIREMENT_PARAM,
        requirement,
        STAT_TYPE_TIMES_PERFORMED_PARAM,
        count,
        COMPLETED_PARAM,
        count >= requirement);
  }

  public record OteCreateData(@Expose String registrarId, @Expose String registrarEmail) {}
}
