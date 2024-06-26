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
package google.registry.loadtest;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.Task;
import google.registry.batch.CloudTasksUtils;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.security.XsrfTokenManager;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import java.util.Collections;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link LoadTestAction}. */
public class LoadTestActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2016-06-13T20:21:22Z"));

  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper(clock);
  private final CloudTasksUtils cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private final FakeResponse response = new FakeResponse();
  private LoadTestAction action;

  private final UserService userService = UserServiceFactory.getUserService();

  private Tld tld;

  @BeforeEach
  void beforeEach() {
    tld = createTld("tld");
    action =
        new LoadTestAction(tld.getTldStr(), new XsrfTokenManager(new FakeClock(), userService));
    action.registrarId = "NewRegistrar";
    action.cloudTasksUtils = cloudTasksUtils;
  }

  @Test
  void test_XmlTemplatesPopulatedCorrectly() {
    action =
        new LoadTestAction(tld.getTldStr(), new XsrfTokenManager(new FakeClock(), userService));
    assertThat(action.xmlContactCreateFail).contains("<contact:id>contact</contact:id>");
    assertThat(action.xmlDomainCreateTmpl).contains("<domain:name>%domain%.tld</domain:name>");
    assertThat(action.xmlDomainInfo)
        .contains("<domain:name hosts=\"all\">testdomain.tld</domain:name>");
  }

  @Test
  void createTasks_hasPayload() {
    List<Task> taskList = action.createTasks(List.of(action.xmlContactCreateFail), clock.nowUtc());
    assertThat(taskList.size()).isEqualTo(1);
    assertThat(taskList.get(0).getHttpRequest().getBody().toString())
        .contains("contents=\"clientId=NewRegistrar&superuser=false");
  }

  @Test
  void createTasks_multipleTasks_hasPayload() {
    List<Task> taskList =
        action.createTasks(
            List.of(action.xmlContactCreateFail, action.xmlContactCreateFail, action.xmlDomainInfo),
            clock.nowUtc());
    assertThat(taskList.size()).isEqualTo(3);
    assertThat(taskList.get(0).getHttpRequest().getBody().toString(UTF_8))
        .contains("contact:create");
    assertThat(taskList.get(2).getHttpRequest().getBody().toString(UTF_8)).contains("domain:info");
  }

  @Test
  void run_successfullyEnqueues() {
    action.runSeconds = 1;
    action.domainInfosPerSecond = 5;
    action.run();
    cloudTasksHelper.assertTasksEnqueued(
        "load0",
        Collections.nCopies(
            5,
            new TaskMatcher()
                .path("/_dr/epptool")
                .service("tools")
                .param("xml", action.xmlDomainInfo)
                .method(HttpMethod.POST)));
  }
}
