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

package google.registry.webdriver;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.server.Fixture.BASIC;
import static google.registry.server.Route.route;
import static google.registry.testing.DatastoreHelper.loadRegistrar;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.ObjectifyFilter;
import google.registry.model.ofy.OfyFilter;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.module.frontend.FrontendServlet;
import google.registry.server.RegistryTestServer;
import google.registry.testing.AppEngineRule;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

/** WebDriver tests for Registrar Console UI. */
@RunWith(RepeatableRunner.class)
public class RegistrarConsoleWebTest extends WebDriverTestCase {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withLocalModules()
      .withTaskQueue()
      .build();

  @Rule
  public final TestServerRule server =
      new TestServerRule.Builder()
          .setRunfiles(RegistryTestServer.RUNFILES)
          .setRoutes(
              route("/registrar", FrontendServlet.class),
              route("/registrar-settings", FrontendServlet.class))
          .setFilters(ObjectifyFilter.class, OfyFilter.class)
          .setFixtures(BASIC)
          .setEmail("Marla.Singer@google.com")
          .build();



  @Rule public final Timeout deathClock = new Timeout(60000, TimeUnit.MILLISECONDS);

  /** Checks the identified element has the given text content. */
  void assertEltText(String eltId, String eltValue) {
    assertThat(driver.findElement(By.id(eltId)).getText()).isEqualTo(eltValue);
  }

  /** Checks that an element is visible. */
  void assertEltVisible(String eltId) throws Throwable {
    assertThat(driver.waitForElement(By.id(eltId)).isDisplayed()).isTrue();
  }

  /** Checks that an element is invisible. */
  void assertEltInvisible(String eltId) throws Throwable {
    assertThat(driver.waitForElement(By.id(eltId)).isDisplayed()).isFalse();
  }

  /** Checks that searching with the given By produces at least one element with the given text. */
  void assertEltTextPresent(By by, String toFind) {
    assertThat(driver.findElements(by).stream().map(WebElement::getText).anyMatch(toFind::equals))
        .isTrue();
  }

  @Test
  public void testEditButtonsVisibility_owner() throws Throwable {
    driver.get(server.getUrl("/registrar#whois-settings"));
    assertEltVisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar#security-settings"));
    assertEltVisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar#contact-settings"));
    assertEltInvisible("reg-app-btns-edit");
    assertEltVisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar#resources"));
    assertEltVisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");
  }

  @Test
  public void testEditButtonsVisibility_adminAndOwner() throws Throwable {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar#whois-settings"));
    assertEltVisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar#security-settings"));
    assertEltVisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar#contact-settings"));
    assertEltInvisible("reg-app-btns-edit");
    assertEltVisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar#admin-settings"));
    assertEltVisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar#resources"));
    assertEltVisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");
  }

  @Test
  public void testEditButtonsVisibility_adminOnly() throws Throwable {
    server.setIsAdmin(true);
    // To make sure we're only ADMIN (and not also "OWNER"), we switch to the NewRegistrar we
    // aren't in the contacts of
    driver.get(server.getUrl("/registrar?clientId=NewRegistrar#whois-settings"));
    assertEltInvisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar?clientId=NewRegistrar#security-settings"));
    assertEltInvisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar?clientId=NewRegistrar#contact-settings"));
    assertEltInvisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar?clientId=NewRegistrar#admin-settings"));
    assertEltVisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");

    driver.get(server.getUrl("/registrar?clientId=NewRegistrar#resources"));
    assertEltInvisible("reg-app-btns-edit");
    assertEltInvisible("reg-app-btn-add");
  }

  @Test
  public void testWhoisSettingsEdit() throws Throwable {
    driver.get(server.getUrl("/registrar#whois-settings"));
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    driver.setFormFieldsById(new ImmutableMap.Builder<String, String>()
        .put("emailAddress", "test1@example.com")
        .put("clientIdentifier", "ignored")
        .put("whoisServer", "foo.bar.baz")
        .put("url", "blah.blar")
        .put("phoneNumber", "+1.2125650000")
        .put("faxNumber", "+1.2125650001")
        .put("localizedAddress.street[0]", "Bőulevard őf")
        .put("localizedAddress.street[1]", "Brőken Dreams")
        .put("localizedAddress.street[2]", "")
        .put("localizedAddress.city", "New York")
        .put("localizedAddress.state", "NY")
        .put("localizedAddress.zip", "10011")
        .put("localizedAddress.countryCode", "US")
        .build());
    driver.findElement(By.id("reg-app-btn-save")).click();
    Thread.sleep(1000);
    Registrar registrar = server.runInAppEngineEnvironment(() -> loadRegistrar("TheRegistrar"));
    assertThat(registrar.getEmailAddress()).isEqualTo("test1@example.com");
    assertThat(registrar.getClientId()).isEqualTo("TheRegistrar");
    assertThat(registrar.getWhoisServer()).isEqualTo("foo.bar.baz");
    assertThat(registrar.getUrl()).isEqualTo("blah.blar");
    assertThat(registrar.getPhoneNumber()).isEqualTo("+1.2125650000");
    assertThat(registrar.getFaxNumber()).isEqualTo("+1.2125650001");
    RegistrarAddress address = registrar.getLocalizedAddress();
    assertThat(address.getStreet()).containsExactly("Bőulevard őf", "Brőken Dreams");
    assertThat(address.getCity()).isEqualTo("New York");
    assertThat(address.getState()).isEqualTo("NY");
    assertThat(address.getZip()).isEqualTo("10011");
    assertThat(address.getCountryCode()).isEqualTo("US");
  }

  @Test
  public void testContactSettingsView() throws Throwable {
    driver.get(server.getUrl("/registrar#contact-settings"));
    driver.waitForElement(By.id("reg-app-btn-add"));
    ImmutableList<RegistrarContact> contacts =
        server.runInAppEngineEnvironment(
            () -> loadRegistrar("TheRegistrar").getContacts().asList());
    for (RegistrarContact contact : contacts) {
      assertEltTextPresent(By.id("contacts[0].name"), contact.getName());
      assertEltTextPresent(By.id("contacts[0].emailAddress"), contact.getEmailAddress());
      assertEltTextPresent(By.id("contacts[0].phoneNumber"), contact.getPhoneNumber());
    }
  }

  @Test
  public void testSecuritySettingsView() throws Throwable {
    driver.get(server.getUrl("/registrar#security-settings"));
    driver.waitForElement(By.id("reg-app-btn-edit"));
    Registrar registrar = server.runInAppEngineEnvironment(() -> loadRegistrar("TheRegistrar"));
    assertThat(driver.findElement(By.id("phonePasscode"))
        .getAttribute("value"))
        .isEqualTo(registrar.getPhonePasscode());
  }
}
