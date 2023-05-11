package google.registry.ui.server.console.settings;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registrar.RegistrarPoc.Type.WHOIS;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static org.junit.jupiter.api.Assertions.*;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.settings.ContactInfoAction.ContactInfoGetAction;
import google.registry.ui.server.console.settings.ContactInfoAction.ContactInfoPostAction;
import google.registry.util.UtilsModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ContactInfoActionTest {

  private static String jsonRegistrar1 =
      "{\"name\":\"Test Registrar 1\","
          + "\"emailAddress\":\"test.registrar1@example.com\","
          + "\"registrarId\":\"registrarId\","
          + "\"registryLockEmailAddress\":\"test.registrar1@external.com\","
          + "\"phoneNumber\":\"+1.9999999999\",\"faxNumber\":\"+1.9999999991\","
          + "\"types\":[\"WHOIS\"],\"visibleInWhoisAsAdmin\":true,"
          + "\"visibleInWhoisAsTech\":false,\"visibleInDomainWhoisAsAbuse\":false,"
          + "\"allowedToSetRegistryLockPassword\":false}";

  private static String jsonRegistrar2 =
      "{\"name\":\"Test Registrar 2\","
          + "\"emailAddress\":\"test.registrar2@example.com\","
          + "\"registrarId\":\"registrarId\","
          + "\"registryLockEmailAddress\":\"test.registrar2@external.com\","
          + "\"phoneNumber\":\"+1.1234567890\",\"faxNumber\":\"+1.1234567891\","
          + "\"types\":[\"WHOIS\"],\"visibleInWhoisAsAdmin\":true,"
          + "\"visibleInWhoisAsTech\":false,\"visibleInDomainWhoisAsAbuse\":false,"
          + "\"allowedToSetRegistryLockPassword\":false}";

  private Registrar testRegistrar;

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
            .setRegistryLockEmailAddress("test.registrar1@external.com")
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
    ContactInfoGetAction action =
        createGetAction(
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(
                        new UserRoles.Builder()
                            .setRegistrarRoles(
                                ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                            .build()))),
            "registrarId");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(RESPONSE.getPayload()).isEqualTo("[" + jsonRegistrar1 + "]");
  }

  @Test
  void testSuccess_postCreateContactInfo() {
    ContactInfoPostAction action =
        createPostAction(
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(
                        new UserRoles.Builder()
                            .setRegistrarRoles(
                                ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                            .build()))),
            "registrarId",
            "[" + jsonRegistrar1 + "," + jsonRegistrar2 + "]");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .anyMatch(registrarPoc -> "Test Registrar 1".equals(registrarPoc.getName())))
        .isEqualTo(true);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .anyMatch(registrarPoc -> "Test Registrar 2".equals(registrarPoc.getName())))
        .isEqualTo(true);
  }

  @Test
  void testSuccess_postUpdateContactInfo() {
    testRegistrarPoc = testRegistrarPoc.asBuilder().setEmailAddress("incorrect@email.com").build();
    insertInDb(testRegistrarPoc);
    ContactInfoPostAction action =
        createPostAction(
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(
                        new UserRoles.Builder()
                            .setRegistrarRoles(
                                ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                            .build()))),
            "registrarId",
            "[" + jsonRegistrar1 + "," + jsonRegistrar2 + "]");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .anyMatch(
                    registrarPoc -> {
                      if ("Test Registrar 1".equals(registrarPoc.getName())) {
                        return registrarPoc.getEmailAddress().equals("test.registrar1@example.com");
                      }
                      return false;
                    }))
        .isEqualTo(true);
  }

  @Test
  void testSuccess_postDeleteContactInfo() {
    insertInDb(testRegistrarPoc);
    ContactInfoPostAction action =
        createPostAction(
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(
                        new UserRoles.Builder()
                            .setRegistrarRoles(
                                ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                            .build()))),
            "registrarId",
            "[" + jsonRegistrar2 + "]");
    action.run();
    assertThat(RESPONSE.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(
            loadAllOf(RegistrarPoc.class).stream()
                .anyMatch(registrarPoc -> "Test Registrar 1".equals(registrarPoc.getName())))
        .isEqualTo(false);
  }

  private User createUser(UserRoles userRoles) {
    return new User.Builder()
        .setEmailAddress("email@email.com")
        .setGaiaId("gaiaId")
        .setUserRoles(userRoles)
        .build();
  }

  private ContactInfoGetAction createGetAction(AuthResult authResult, String registrarId) {
    return new ContactInfoGetAction(authResult, RESPONSE, GSON, registrarId);
  }

  private ContactInfoPostAction createPostAction(
      AuthResult authResult, String registrarId, String contacts) {
    return new ContactInfoPostAction(authResult, RESPONSE, GSON, registrarId, contacts);
  }
}
