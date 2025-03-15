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

package google.registry.tools.server;

import static com.google.common.collect.Maps.toMap;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import google.registry.model.OteStats;
import google.registry.model.OteStats.StatType;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.JsonActionRunner;
import google.registry.request.JsonActionRunner.JsonAction;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A servlet that verifies a registrar's OTE status. Note that this is eventually consistent, so
 * OT&amp;E commands that have been run just previously to verification may not be picked up yet.
 */
@Action(
    service = GaeService.TOOLS,
    path = VerifyOteAction.PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_ADMIN)
public class VerifyOteAction implements Runnable, JsonAction {

  public static final String PATH = "/_dr/admin/verifyOte";

  @Inject JsonActionRunner jsonActionRunner;

  @Inject
  VerifyOteAction() {}

  @Override
  public void run() {
    jsonActionRunner.run(this);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> handleJsonRequest(Map<String, ?> json) {
    final boolean summarize = Boolean.parseBoolean((String) json.get("summarize"));

    Map<String, OteStats> registrarResults =
        toMap((List<String>) json.get("registrars"), OteStats::getFromRegistrar);
    return Maps.transformValues(registrarResults, stats -> transformOteStats(stats, summarize));
  }

  private String transformOteStats(OteStats stats, boolean summarize) {
    List<StatType> failures = stats.getFailures();
    int numRequiredTests = StatType.REQUIRED_STAT_TYPES.size();
    int testsPassed = numRequiredTests - failures.size();

    String status = failures.isEmpty() ? "PASS" : "FAIL";
    return summarize
        ? String.format(
            "# actions: %4d - Reqs: [%s] %2d/%2d - Overall: %s",
            stats.getSize(), getSummary(stats), testsPassed, numRequiredTests, status)
        : String.format(
            "%s\n%s\nRequirements passed: %2d/%2d\nOverall OT&E status: %s\n",
            stats, Joiner.on('\n').join(failures), testsPassed, numRequiredTests, status);
  }

  private String getSummary(OteStats stats) {
    return StatType.REQUIRED_STAT_TYPES.stream()
        .sorted()
        .map(statType -> (stats.getCount(statType) < statType.getRequirement()) ? "." : "-")
        .collect(Collectors.joining(""));
  }
}
