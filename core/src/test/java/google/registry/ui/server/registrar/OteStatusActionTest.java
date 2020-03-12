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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.persistNewRegistrar;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import google.registry.model.OteAccountBuilder;
import google.registry.model.OteStats.StatType;
import google.registry.model.OteStatsTestHelper;
import google.registry.model.registrar.Registrar.Type;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.testing.AppEngineRule;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link OteStatusAction} */
@RunWith(JUnit4.class)
public final class OteStatusActionTest {

  private static final String CLIENT_ID = "blobio-1";
  private static final String BASE_CLIENT_ID = "blobio";

  private final OteStatusAction action = new OteStatusAction();

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Before
  public void init() {
    ImmutableSetMultimap<String, Role> authValues =
        OteAccountBuilder.createClientIdToTldMap(BASE_CLIENT_ID).keySet().stream()
            .collect(toImmutableSetMultimap(Function.identity(), ignored -> Role.OWNER));
    action.registrarAccessor = AuthenticatedRegistrarAccessor.createForTesting(authValues);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSuccess_finishedOte() throws Exception {
    OteStatsTestHelper.setupCompleteOte(BASE_CLIENT_ID);

    Map<String, ?> actionResult = action.handleJsonRequest(ImmutableMap.of("clientId", CLIENT_ID));
    assertThat(actionResult).containsEntry("status", "SUCCESS");
    assertThat(actionResult).containsEntry("message", "OT&E check completed successfully");
    Map<String, ?> results =
        Iterables.getOnlyElement((List<Map<String, ?>>) actionResult.get("results"));
    assertThat(results).containsEntry("clientId", BASE_CLIENT_ID);
    assertThat(results).containsEntry("completed", true);
    assertThat(getFailingResultDetails(results)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSuccess_incomplete() throws Exception {
    OteStatsTestHelper.setupIncompleteOte(BASE_CLIENT_ID);

    Map<String, ?> actionResult = action.handleJsonRequest(ImmutableMap.of("clientId", CLIENT_ID));
    assertThat(actionResult).containsEntry("status", "SUCCESS");
    assertThat(actionResult).containsEntry("message", "OT&E check completed successfully");
    Map<String, ?> results =
        Iterables.getOnlyElement((List<Map<String, ?>>) actionResult.get("results"));
    assertThat(results).containsEntry("clientId", BASE_CLIENT_ID);
    assertThat(results).containsEntry("completed", false);
    assertThat(getFailingResultDetails(results))
        .containsExactly(
            ImmutableMap.of(
                "description", StatType.HOST_DELETES.getDescription(),
                "requirement", StatType.HOST_DELETES.getRequirement(),
                "timesPerformed", 0,
                "completed", false),
            ImmutableMap.of(
                "description", StatType.DOMAIN_RESTORES.getDescription(),
                "requirement", StatType.DOMAIN_RESTORES.getRequirement(),
                "timesPerformed", 0,
                "completed", false),
            ImmutableMap.of(
                "description", StatType.DOMAIN_CREATES_IDN.getDescription(),
                "requirement", StatType.DOMAIN_CREATES_IDN.getRequirement(),
                "timesPerformed", 0,
                "completed", false));
  }

  @Test
  public void testFailure_malformedInput() {
    assertThat(action.handleJsonRequest(null))
        .containsExactlyEntriesIn(errorResultWithMessage("Malformed JSON"));
    assertThat(action.handleJsonRequest(ImmutableMap.of()))
        .containsExactlyEntriesIn(errorResultWithMessage("Missing key for OT&E client: clientId"));
  }

  @Test
  public void testFailure_registrarDoesntExist() {
    assertThat(action.handleJsonRequest(ImmutableMap.of("clientId", "nonexistent-3")))
        .containsExactlyEntriesIn(
            errorResultWithMessage("Registrar nonexistent-3 does not exist"));
  }

  @Test
  public void testFailure_notAuthorized() {
    persistNewRegistrar(CLIENT_ID, "blobio-1", Type.REAL, 1L);
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
    assertThat(action.handleJsonRequest(ImmutableMap.of("clientId", CLIENT_ID)))
        .containsExactlyEntriesIn(
            errorResultWithMessage("TestUserId doesn't have access to registrar blobio-1"));
  }

  @Test
  public void testFailure_malformedRegistrarName() {
    assertThat(action.handleJsonRequest(ImmutableMap.of("clientId", "badclient-id")))
        .containsExactlyEntriesIn(
            errorResultWithMessage(
                "ID badclient-id is not one of the OT&E client IDs for base badclient"));
  }

  @Test
  public void testFailure_nonOteRegistrar() {
    persistNewRegistrar(CLIENT_ID, "SomeRegistrar", Type.REAL, 1L);
    assertThat(action.handleJsonRequest(ImmutableMap.of("clientId", CLIENT_ID)))
        .containsExactlyEntriesIn(
            errorResultWithMessage("Registrar with ID blobio-1 is not an OT&E registrar"));
  }

  @SuppressWarnings("unchecked")
  private List<Map<?, ?>> getFailingResultDetails(Map<String, ?> results) {
    return ((List<Map<?, ?>>) results.get("details"))
        .stream()
        .filter(result -> !Boolean.TRUE.equals(result.get("completed")))
        .collect(toImmutableList());
  }

  private ImmutableMap<String, ?> errorResultWithMessage(String message) {
    return ImmutableMap.of("status", "ERROR", "message", message, "results", ImmutableList.of());
  }
}
