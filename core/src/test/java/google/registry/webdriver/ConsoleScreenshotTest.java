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

package google.registry.webdriver;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.server.Fixture.BASIC;

import com.google.common.collect.ImmutableMap;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.server.RegistryTestServer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.RetryingTest;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

/**
 * Tests for the 2024 registrar console, served from the Angular staged distribution.
 *
 * <p>When running the tests locally, be sure to build the console webapp first (the
 * "buildConsoleWebapp" Gradle command, or the "npm run build" command) otherwise the tests will
 * fail.
 *
 * <p>In general, for tests, we load the home page then click on elements to transfer pages. This is
 * because we aren't really serving the webapp the same way that an actual webserver (e.g. Express)
 * would, we're just statically serving the distribution. As a result, we cannot load URLs, e.g.
 * "/#/settings", directly. Put another way, because we don't have a service actually serving the
 * console, we have to access /console/index.html directly to load the raw file itself, rather than
 * accessing /console.
 *
 * <p>NB: the calls to Thread.sleep are unfortunate but seem to be necessary. Often when we click on
 * something, that element is highlighted for a very small amount of time after we click it, with
 * the color fading over a short period of time. We must wait for the highlighting to fade to get
 * cnosistent images to avoid spurious failures.
 */
public class ConsoleScreenshotTest extends WebDriverTestCase {

  @RegisterExtension
  final TestServerExtension server =
      new TestServerExtension.Builder()
          .setRunfiles(RegistryTestServer.RUNFILES)
          .setRoutes(RegistryTestServer.ROUTES)
          .setFixtures(BASIC)
          .setEmail("Marla.Singer@crr.com") // from makeRegistrarContact3
          .setRegistryLockEmail("Marla.Singer.RegistryLock@crr.com")
          .build();

  @BeforeEach
  void beforeEach() throws Exception {
    server.setRegistrarRoles(ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER));
    loadHomePage();
  }

  @RetryingTest(3)
  void index() throws Exception {
    driver.diffPage("page");
  }

  @RetryingTest(3)
  void index_registrarSelectDropdown() throws Exception {
    selectRegistrar();
    driver.diffPage("selectedRegistrar");
    assertThat(driver.getCurrentUrl()).endsWith("?registrarId=TheRegistrar");
  }

  @RetryingTest(3)
  void dums_mainPage() throws Exception {
    clickSidebarElementByName("Domains");
    driver.diffPage("noRegistrarSelected");
    selectRegistrar();
    driver.waitForElementToNotExist(By.tagName("mat-spinner"));
    Thread.sleep(50);
    driver.diffPage("registrarSelected");
    driver.findElement(By.cssSelector("mat-table button")).click();
    Thread.sleep(100);
    driver.diffPage("actionsButtonClicked");
  }


  @RetryingTest(3)
  void settingsPage() throws Exception {
    clickSidebarElementByName("Settings");
    driver.diffPage("noRegistrarSelected");
    selectRegistrar();
    driver.diffPage("registrarSelected_contacts");
    driver.findElement(By.cssSelector("a[routerLink=\"whois\"]")).click();
    Thread.sleep(500);
    driver.diffPage("registrarSelected_whois");
    driver.findElement(By.cssSelector("a[routerLink=\"security\"]")).click();
    Thread.sleep(500);
    driver.diffPage("registrarSelected_security");
  }

  @RetryingTest(3)
  void billingInfo() throws Exception {
    clickSidebarElementByName("Billing Info");
    driver.diffPage("noRegistrarSelected");
    selectRegistrar();
    driver.diffPage("registrarSelected");
  }

  @RetryingTest(3)
  void resources() throws Exception {
    clickSidebarElementByName("Resources");
    driver.diffPage("page");
  }

  @RetryingTest(3)
  void support() throws Exception {
    clickSidebarElementByName("Support");
    driver.diffPage("page");
  }

  @RetryingTest(3)
  void globalRole_registrars() throws Exception {
    server.setGlobalRole(GlobalRole.SUPPORT_LEAD);
    loadHomePage();
    driver.diffPage("homePage");
    clickSidebarElementByName("Registrars");
    driver.diffPage("registrarsPage");
  }

  private void clickSidebarElementByName(String name) throws Exception {
    WebElement appContainer =
        driver.findElement(By.cssSelector("mat-sidenav-container.console-app__container"));
    List<WebElement> sidebarElems =
        appContainer.findElements(By.cssSelector("mat-tree-node,mat-nested-tree-node"));
    for (WebElement elem : sidebarElems) {
      if (elem.getText().contains(name)) {
        elem.click();
        break;
      }
    }
    Thread.sleep(100);
  }

  private void loadHomePage() throws InterruptedException {
    driver.get(server.getUrl("/console/index.html"));
    driver.waitForElementToNotExist(By.tagName("mat-progress-bar"));
    // Script that set cursor to transparent to prevent blanking cursor flakiness when comparing
    // screenshots
    String script =
        "document.styleSheets[0].insertRule(\"html * {caret-color: transparent !important;}\")";
    driver.executeScript(script);
  }

  private void selectRegistrar() throws InterruptedException {
    driver.findElement(By.cssSelector("div.console-app__registrar input")).click();
    Thread.sleep(200);
    driver.diffPage("selectorOpen");
    driver.findElement(By.cssSelector("div.mat-mdc-autocomplete-panel mat-option")).click();
    Thread.sleep(200);
  }
}
