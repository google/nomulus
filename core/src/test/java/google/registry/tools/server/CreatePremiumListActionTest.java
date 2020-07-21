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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.registry.label.PremiumListUtils.deletePremiumList;
import static google.registry.model.registry.label.PremiumListUtils.getPremiumPrice;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.loadPremiumListEntries;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeJsonResponse;
import org.joda.money.Money;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link CreatePremiumListAction}.
 */
@RunWith(JUnit4.class)
public class CreatePremiumListActionTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  CreatePremiumListAction action;
  FakeJsonResponse response;

  @Before
  public void init() {
    createTlds("foo", "xn--q9jyb4c", "how");
    deletePremiumList(PremiumList.getUncached("foo").get());
    action = new CreatePremiumListAction();
    response = new FakeJsonResponse();
    action.response = response;
  }

  @Test
  public void test_invalidRequest_missingInput_returnsErrorStatus() {
    action.name = "foo";
    action.run();
    assertThat(response.getResponseMap().get("status")).isEqualTo("error");
  }

  @Test
  public void test_invalidRequest_listAlreadyExists_returnsErrorStatus() {
    action.name = "how";
    action.inputData = "richer,JPY 5000";
    action.run();
    assertThat(response.getResponseMap().get("status")).isEqualTo("error");
    Object obj = response.getResponseMap().get("error");
    assertThat(obj).isInstanceOf(String.class);
    String error = obj.toString();
    assertThat(error).contains("A premium list of this name already exists");
  }

  @Test
  public void test_nonExistentTld_successWithOverride() {
    action.name = "zanzibar";
    action.inputData = "zanzibar,USD 100";
    action.override = true;
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadPremiumListEntries(PremiumList.getUncached("zanzibar").get())).hasSize(1);
  }

  @Test
  public void test_success() {
    action.name = "foo";
    action.inputData = "rich,USD 25\nricher,USD 1000\n";
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadPremiumListEntries(PremiumList.getUncached("foo").get())).hasSize(2);
    assertThat(getPremiumPrice("rich", Registry.get("foo"))).hasValue(Money.parse("USD 25"));
    assertThat(getPremiumPrice("diamond", Registry.get("foo"))).isEmpty();
  }
}
