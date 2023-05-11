package google.registry.ui.server.console.settings;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registrar.RegistrarPoc.Type.WHOIS;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.FakeResponse;
import google.registry.util.UtilsModule;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.ui.server.console.ConsoleDomainGetAction}. */
public class ContactGetActionTest {
  private static String jsonRegistrar1 =
      "{\"name\":\"Test Registrar 1\","
          + "\"emailAddress\":\"test.registrar1@example.com\","
          + "\"registrarId\":\"registrarId\","
          + "\"phoneNumber\":\"+1.9999999999\",\"faxNumber\":\"+1.9999999991\","
          + "\"types\":[\"WHOIS\"],\"visibleInWhoisAsAdmin\":true,"
          + "\"visibleInWhoisAsTech\":false,\"visibleInDomainWhoisAsAbuse\":false}";

  private static String jsonRegistrar2 =
      "{\"name\":\"Test Registrar 2\","
          + "\"emailAddress\":\"test.registrar2@example.com\","
          + "\"registrarId\":\"registrarId\","
          + "\"phoneNumber\":\"+1.1234567890\",\"faxNumber\":\"+1.1234567891\","
          + "\"types\":[\"WHOIS\"],\"visibleInWhoisAsAdmin\":true,"
          + "\"visibleInWhoisAsTech\":false,\"visibleInDomainWhoisAsAbuse\":false}";

  private Registrar testRegistrar;
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private RegistrarPoc testRegistrarPoc;
  private static final Gson GSON = UtilsModule.provideGson();
  private static final FakeResponse RESPONSE = new FakeResponse();

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    testRegistrar = saveRegistrar("registrarId");
    testRegistrarPoc =
        new RegistrarPoc.Builder()
            .setRegistrar(testRegistrar)
            .setName("Test Registrar 1")
            .setEmailAddress("test.registrar1@example.com")
            .setPhoneNumber("+1.9999999999")
            .setFaxNumber("+1.9999999991")
            .setTypes(ImmutableSet.of(WHOIS))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(false)
            .setVisibleInDomainWhoisAsAbuse(false)
            .build();
  }

  @Test
  void testSuccess_getContactInfo() {
    insertInDb(testRegistrarPoc);
    ContactGetAction action =
        createGetAction(
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build()))),
            "registrarId");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(RESPONSE.getPayload()).isEqualTo("[" + jsonRegistrar1 + "]");
  }

  private User createUser(UserRoles userRoles) {
    return new User.Builder()
        .setEmailAddress("email@email.com")
        .setGaiaId("gaiaId")
        .setUserRoles(userRoles)
        .build();
  }

  private ContactGetAction createGetAction(AuthResult authResult, String registrarId) {
    return new ContactGetAction(authResult, RESPONSE, GSON, registrarId);
  }
}
