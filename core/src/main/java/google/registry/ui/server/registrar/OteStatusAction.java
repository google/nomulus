// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.security.JsonResponseHelper.Status.ERROR;
import static google.registry.security.JsonResponseHelper.Status.SUCCESS;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import google.registry.model.OteAccountBuilder;
import google.registry.model.OteStats;
import google.registry.model.OteStats.StatType;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.Type;
import google.registry.request.Action;
import google.registry.request.JsonActionRunner;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.security.JsonResponseHelper;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Admin servlet that allows creating or updating a registrar. Deletes are not allowed so as to
 * preserve history.
 */
@Action(
    service = Action.Service.DEFAULT,
    path = OteStatusAction.PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public final class OteStatusAction implements Runnable, JsonActionRunner.JsonAction {

  public static final String PATH = "/registrar-ote-status";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String CLIENT_ID_PARAM = "clientId";
  private static final String COMPLETED_PARAM = "completed";
  private static final String DETAILS_PARAM = "details";
  private static final String STAT_TYPE_DESCRIPTION_PARAM = "description";
  private static final String STAT_TYPE_REQUIREMENT_PARAM = "requirement";
  private static final String STAT_TYPE_TIMES_PERFORMED_PARAM = "timesPerformed";

  @Inject AuthenticatedRegistrarAccessor registrarAccessor;
  @Inject JsonActionRunner jsonActionRunner;

  @Inject
  OteStatusAction() {}

  @Override
  public void run() {
    jsonActionRunner.run(this);
  }

  @Override
  public Map<String, ?> handleJsonRequest(Map<String, ?> input) {
    try {
      checkArgument(input != null, "Malformed JSON");

      String oteClientId = (String) input.get(CLIENT_ID_PARAM);
      checkArgument(
          !Strings.isNullOrEmpty(oteClientId), "Missing key for OT&E client: %s", CLIENT_ID_PARAM);

      String baseClientId = OteAccountBuilder.getBaseRegistrarId(oteClientId);
      Registrar oteRegistrar = registrarAccessor.getRegistrar(oteClientId);
      verifyOteAccess(baseClientId);
      checkArgument(
          Type.OTE.equals(oteRegistrar.getType()),
          "Registrar with ID %s is not an OT&E registrar",
          oteClientId);

      OteStats oteStats = OteStats.getFromRegistrar(baseClientId);
      return JsonResponseHelper.create(
          SUCCESS, "OT&E check completed successfully", convertOteStats(baseClientId, oteStats));
    } catch (Throwable e) {
      logger.atWarning().withCause(e).log(
          "Failed to verify OT&E status for registrar with input: %s", input);
      return JsonResponseHelper.create(
          ERROR, Optional.ofNullable(e.getMessage()).orElse("Unspecified error"));
    }
  }

  private void verifyOteAccess(String baseClientId) throws RegistrarAccessDeniedException {
    for (String oteClientId : OteAccountBuilder.createRegistrarIdToTldMap(baseClientId).keySet()) {
      registrarAccessor.verifyAccess(oteClientId);
    }
  }

  private Map<String, Object> convertOteStats(String baseClientId, OteStats oteStats) {
    return ImmutableMap.of(
        CLIENT_ID_PARAM, baseClientId,
        COMPLETED_PARAM, oteStats.getFailures().isEmpty(),
        DETAILS_PARAM,
            StatType.REQUIRED_STAT_TYPES.stream()
                .map(statType -> convertSingleRequirement(statType, oteStats.getCount(statType)))
                .collect(toImmutableList()));
  }

  private Map<String, Object> convertSingleRequirement(StatType statType, int count) {
    int requirement = statType.getRequirement();
    return ImmutableMap.of(
        STAT_TYPE_DESCRIPTION_PARAM, statType.getDescription(),
        STAT_TYPE_REQUIREMENT_PARAM, requirement,
        STAT_TYPE_TIMES_PERFORMED_PARAM, count,
        COMPLETED_PARAM, count >= requirement);
  }
}
