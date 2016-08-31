// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import static google.registry.security.JsonHttpTestUtils.createJsonPayload;
import static google.registry.security.JsonHttpTestUtils.createJsonResponseSupplier;
import static google.registry.security.XsrfTokenManager.generateToken;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import com.google.appengine.api.modules.ModulesService;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import google.registry.export.sheet.SyncRegistrarsSheetAction;
import google.registry.model.ofy.Ofy;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ExceptionRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import google.registry.util.SendEmailService;
import google.registry.util.SendEmailUtils;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.Properties;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Base class for tests using {@link RegistrarServlet}. */
@RunWith(MockitoJUnitRunner.class)
public class RegistrarServletTestCase {

  static final String CLIENT_ID = "TheRegistrar";

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue()
      .build();

  @Rule
  public final InjectRule inject = new InjectRule();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Mock
  HttpServletRequest req;

  @Mock
  HttpServletResponse rsp;

  @Mock
  SessionUtils sessionUtils;

  @Mock
  SendEmailService emailService;

  @Mock
  ModulesService modulesService;

  Message message;

  final RegistrarServlet servlet = new RegistrarServlet();
  final StringWriter writer = new StringWriter();
  final Supplier<Map<String, Object>> json = createJsonResponseSupplier(writer);
  final FakeClock clock = new FakeClock(DateTime.parse("2014-01-01T00:00:00Z"));

  @Before
  public void setUp() throws Exception {
    inject.setStaticField(Ofy.class, "clock", clock);
    inject.setStaticField(ResourceServlet.class, "sessionUtils", sessionUtils);
    inject.setStaticField(SendEmailUtils.class, "emailService", emailService);
    inject.setStaticField(SyncRegistrarsSheetAction.class, "modulesService", modulesService);
    message = new MimeMessage(Session.getDefaultInstance(new Properties(), null));
    when(emailService.createMessage()).thenReturn(message);
    when(req.getMethod()).thenReturn("POST");
    when(rsp.getWriter()).thenReturn(new PrintWriter(writer));
    when(req.getContentType()).thenReturn("application/json");
    when(req.getHeader(eq("X-CSRF-Token"))).thenReturn(generateToken("console"));
    when(req.getReader()).thenReturn(createJsonPayload(ImmutableMap.of("op", "read")));
    when(sessionUtils.isLoggedIn()).thenReturn(true);
    when(sessionUtils.checkRegistrarConsoleLogin(req)).thenReturn(true);
    when(sessionUtils.getRegistrarClientId(req)).thenReturn(CLIENT_ID);
    when(modulesService.getVersionHostname("backend", null)).thenReturn("backend.hostname");
  }
}
