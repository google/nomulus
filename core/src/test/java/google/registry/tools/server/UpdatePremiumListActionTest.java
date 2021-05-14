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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.loadPremiumListEntries;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Splitter;
import com.google.common.truth.Truth8;
import google.registry.model.registry.label.PremiumList;
import google.registry.schema.tld.PremiumListDao;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeJsonResponse;
import java.math.BigDecimal;
import java.util.List;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link UpdatePremiumListAction}. */
class UpdatePremiumListActionTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private UpdatePremiumListAction action;
  private FakeJsonResponse response;

  @BeforeEach
  void beforeEach() {
    createTlds("foo", "xn--q9jyb4c", "how");
    action = new UpdatePremiumListAction();
    response = new FakeJsonResponse();
    action.response = response;
  }

  @Test
  void test_invalidRequest_missingInput_returnsErrorStatus() {
    action.name = "foo";
    action.run();
    assertThat(response.getResponseMap().get("status")).isEqualTo("error");
  }

  @Test
  void test_invalidRequest_listDoesNotExist_returnsErrorStatus() {
    action.name = "bamboozle";
    action.inputData = "richer,JPY 5000";
    action.run();
    assertThat(response.getResponseMap().get("status")).isEqualTo("error");
    Object obj = response.getResponseMap().get("error");
    assertThat(obj).isInstanceOf(String.class);
    String error = obj.toString();
    assertThat(error).contains("Could not update premium list");
  }

  @Test
  void test_success() {
    List<String> inputLines =
        Splitter.on('\n')
            .omitEmptyStrings()
            .splitToList(
                readResourceUtf8(DatabaseHelper.class, "default_premium_list_testdata.csv"));
    PremiumListDao.save("foo", inputLines);
    action.name = "foo";
    action.inputData = "rich,USD 75\nricher,USD 5000\npoor, USD 0.99";
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadPremiumListEntries(PremiumListDao.getLatestRevision("foo").get())).hasSize(3);
    Truth8.assertThat(PremiumListDao.getPremiumPrice("foo", "rich"))
        .hasValue(Money.parse("USD 75"));
    Truth8.assertThat(PremiumListDao.getPremiumPrice("foo", "richer"))
        .hasValue(Money.parse("USD 5000"));
    Truth8.assertThat(PremiumListDao.getPremiumPrice("foo", "poor"))
        .hasValue(Money.parse("USD 0.99"));
    Truth8.assertThat(PremiumListDao.getPremiumPrice("foo", "diamond")).isEmpty();

    jpaTm()
        .transact(
            () -> {
              PremiumList persistedList = PremiumListDao.getLatestRevision("foo").get();
              assertThat(persistedList.getLabelsToPrices())
                  .containsEntry("rich", new BigDecimal("75.00"));
              assertThat(persistedList.getLabelsToPrices())
                  .containsEntry("richer", new BigDecimal("5000.00"));
              assertThat(persistedList.getLabelsToPrices())
                  .containsEntry("poor", BigDecimal.valueOf(0.99));
              assertThat(persistedList.getLabelsToPrices()).doesNotContainKey("diamond");
            });
  }
}
