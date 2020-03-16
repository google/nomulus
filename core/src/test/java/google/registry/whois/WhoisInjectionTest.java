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

package google.registry.whois;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHostResource;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.request.RequestModule;
import google.registry.testing.AppEngineRule;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for Dagger injection of the whois package. */
@RunWith(JUnit4.class)
public final class WhoisInjectionTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  private final HttpServletRequest req = mock(HttpServletRequest.class);
  private final HttpServletResponse rsp = mock(HttpServletResponse.class);
  private final StringWriter httpOutput = new StringWriter();

  @Before
  public void setUp() throws Exception {
    when(rsp.getWriter()).thenReturn(new PrintWriter(httpOutput));
  }

  @Test
  public void testWhoisAction_injectsAndWorks() throws Exception {
    createTld("lol");
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("ns1.cat.lol\r\n")));
    DaggerWhoisTestComponent.builder()
        .requestModule(new RequestModule(req, rsp))
        .build()
        .whoisAction()
        .run();
    verify(rsp).setStatus(200);
    assertThat(httpOutput.toString()).contains("ns1.cat.lol");
  }

  @Test
  public void testWhoisHttpAction_injectsAndWorks() {
    createTld("lol");
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    when(req.getRequestURI()).thenReturn("/whois/ns1.cat.lol");
    DaggerWhoisTestComponent.builder()
        .requestModule(new RequestModule(req, rsp))
        .build()
        .whoisHttpAction()
        .run();
    verify(rsp).setStatus(200);
    assertThat(httpOutput.toString()).contains("ns1.cat.lol");
  }
}
