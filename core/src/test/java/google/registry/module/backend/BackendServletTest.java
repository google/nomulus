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

package google.registry.module.backend;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.testing.AppEngineRule;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BackendServlet}. */
@RunWith(JUnit4.class)
public class BackendServletTest {

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withLocalModules().build();

  private final HttpServletRequest req = mock(HttpServletRequest.class);
  private final HttpServletResponse rsp = mock(HttpServletResponse.class);

  @Test
  public void testService_unknownPath_returnsNotFound() throws Exception {
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/lol");
    new BackendServlet().service(req, rsp);
    verify(rsp).sendError(404);
  }
}
