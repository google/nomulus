// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.server.Fixture.BASIC;
import static google.registry.server.Route.route;

import com.googlecode.objectify.ObjectifyFilter;
import google.registry.model.ofy.OfyFilter;
import google.registry.module.frontend.FrontendServlet;
import google.registry.server.RegistryTestServer;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.RetryingTest;
import org.openqa.selenium.By;

/** Registrar Console Screenshot Differ tests. */
class RegistrarCreateConsoleScreenshotTest extends WebDriverTestCase {

  @RegisterExtension
  final TestServerExtension server =
      new TestServerExtension.Builder()
          .setRunfiles(RegistryTestServer.RUNFILES)
          .setRoutes(route("/registrar-create", FrontendServlet.class))
          .setFilters(ObjectifyFilter.class, OfyFilter.class)
          .setFixtures(BASIC)
          .setEmail("Marla.Singer@google.com")
          .build();

  @RetryingTest(3)
  void get_owner_fails() throws Throwable {
    driver.get(server.getUrl("/registrar-create"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("unauthorized");
  }

  @RetryingTest(3)
  void get_admin_succeeds() throws Throwable {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar-create"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("formEmpty");
    driver.findElement(By.id("clientId")).sendKeys("my-name");
    driver.findElement(By.id("name")).sendKeys("registrar name");
    driver
        .findElement(By.id("billingAccount"))
        .sendKeys(""
            + "USD=12345678-abcd-1234-5678-cba987654321\n"
            + "JPY=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    driver.findElement(By.id("driveId")).sendKeys("drive-id");
    driver.findElement(By.id("ianaId")).sendKeys("15263");
    driver.findElement(By.id("referralEmail")).sendKeys("email@icann.example");
    driver.findElement(By.id("consoleUserEmail")).sendKeys("my-name@registry.example");
    driver.findElement(By.id("street1")).sendKeys("123 Street st.");
    driver.findElement(By.id("city")).sendKeys("Citysville");
    driver.findElement(By.id("countryCode")).sendKeys("fr");
    driver.findElement(By.id("password")).sendKeys("StRoNgPaSsWoRd");
    driver.findElement(By.id("passcode")).sendKeys("01234");
    driver.diffPage("formFilled");
    driver.findElement(By.id("submit-button")).click();
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("createResult");
  }

  @RetryingTest(3)
  void get_admin_fails_badEmail() throws Throwable {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar-create"));
    driver.waitForElement(By.tagName("h1"));
    driver.findElement(By.id("clientId")).sendKeys("my-name");
    driver.findElement(By.id("name")).sendKeys("registrar name");
    driver
        .findElement(By.id("billingAccount"))
        .sendKeys(""
            + "USD=12345678-abcd-1234-5678-cba987654321\n"
            + "JPY=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    driver.findElement(By.id("driveId")).sendKeys("drive-id");
    driver.findElement(By.id("ianaId")).sendKeys("15263");
    driver.findElement(By.id("referralEmail")).sendKeys("email@icann.example");
    driver.findElement(By.id("consoleUserEmail")).sendKeys("bad email");
    driver.findElement(By.id("street1")).sendKeys("123 Street st.");
    driver.findElement(By.id("city")).sendKeys("Citysville");
    driver.findElement(By.id("countryCode")).sendKeys("fr");
    driver.findElement(By.id("submit-button")).click();
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("createResultFailed");
  }
}
