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
import static google.registry.testing.AppEngineRule.makeRegistrar2;
import static google.registry.testing.AppEngineRule.makeRegistrarContact2;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.ObjectifyFilter;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.OfyFilter;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.RegistrarContact;
import google.registry.module.frontend.FrontendServlet;
import google.registry.schema.domain.RegistryLock;
import google.registry.server.RegistryTestServer;
import google.registry.testing.AppEngineRule;
import google.registry.testing.CertificateSamples;
import java.util.Optional;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;

/** Registrar Console Screenshot Differ tests. */
@RunWith(RepeatableRunner.class)
public class RegistrarConsoleScreenshotTest extends WebDriverTestCase {

  @Rule
  public final TestServerRule server =
      new TestServerRule.Builder()
          .setRunfiles(RegistryTestServer.RUNFILES)
          .setRoutes(
              route("/registrar", FrontendServlet.class),
              route("/registrar-ote-status", FrontendServlet.class),
              route("/registrar-settings", FrontendServlet.class),
              route("/registry-lock-get", FrontendServlet.class),
              route("/registry-lock-verify", FrontendServlet.class))
          .setFilters(ObjectifyFilter.class, OfyFilter.class)
          .setFixtures(BASIC)
          .setEmail("Marla.Singer@crr.com") // from AppEngineRule.makeRegistrarContact3
          .setGaeUserId("12345") // from AppEngineRule.makeRegistrarContact3
          .build();

  @Test
  public void index_owner() throws Throwable {
    driver.get(server.getUrl("/registrar"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("page");
  }

  /** Admins have an extra "admin" tab. */
  @Test
  public void index_adminAndOwner() throws Throwable {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("page");
  }

  /** Admins who aren't owners still have all the tabs. */
  @Test
  public void index_admin() throws Throwable {
    server.setIsAdmin(true);
    // To make sure we're only ADMIN (and not also "OWNER"), we switch to the NewRegistrar we
    // aren't in the contacts of
    driver.get(server.getUrl("/registrar?clientId=NewRegistrar"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("page");
  }

  @Test
  public void contactUs() throws Throwable {
    driver.get(server.getUrl("/registrar#contact-us"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("page");
  }

  @Test
  public void settingsContact() throws Throwable {
    driver.get(server.getUrl("/registrar#contact-settings"));
    Thread.sleep(1000);
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("page");
  }

  /** Admins shouldn't have the "add" button */
  @Test
  public void settingsContact_asAdmin() throws Throwable {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar?clientId=NewRegistrar#contact-settings"));
    Thread.sleep(1000);
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("page");
  }

  @Test
  public void settingsContactItem() throws Throwable {
    driver.get(server.getUrl("/registrar#contact-settings/johndoe@theregistrar.com"));
    Thread.sleep(1000);
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("page");
  }

  /** Admins shouldn't have the "edit" button */
  @Test
  public void settingsContactItem_asAdmin() throws Throwable {
    server.setIsAdmin(true);
    driver.get(
        server.getUrl(
            "/registrar?clientId=NewRegistrar#contact-settings/janedoe@theregistrar.com"));
    Thread.sleep(1000);
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("page");
  }

  @Test
  public void settingsContactEdit() throws Throwable {
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#contact-settings/johndoe@theregistrar.com"));
    Thread.sleep(1000);
    driver.waitForElement(By.tagName("h1"));
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    driver.diffPage("page");
  }

  @Test
  public void settingsContactEdit_setRegistryLockPassword() throws Throwable {
    server.runInAppEngineEnvironment(
        () -> {
          persistResource(makeRegistrar2().asBuilder().setRegistryLockAllowed(true).build());
          persistResource(
              makeRegistrarContact2()
                  .asBuilder()
                  .setRegistryLockEmailAddress("johndoe.registrylock@example.com")
                  .setAllowedToSetRegistryLockPassword(true)
                  .build());
          return null;
        });
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#contact-settings/johndoe@theregistrar.com"));
    Thread.sleep(1000);
    driver.waitForElement(By.tagName("h1"));
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    driver.diffPage("page");

    // now actually set the password
    driver.findElement(By.id("contacts[1].registryLockPassword")).sendKeys("password");
    driver.waitForElement(By.id("reg-app-btn-save")).click();
    Thread.sleep(500);
    driver.diffPage("contactview");

    server.runInAppEngineEnvironment(
        () -> {
          RegistrarContact contact =
              loadRegistrar("TheRegistrar").getContacts().stream()
                  .filter(c -> c.getEmailAddress().equals("johndoe@theregistrar.com"))
                  .findFirst()
                  .get();
          assertThat(contact.verifyRegistryLockPassword("password")).isTrue();
          assertThat(contact.getRegistryLockEmailAddress())
              .isEqualTo(Optional.of("johndoe.registrylock@example.com"));
          return null;
        });
  }

  @Test
  public void settingsContactEdit_setRegistryLockPassword_alreadySet() throws Throwable {
    server.runInAppEngineEnvironment(
        () -> {
          persistResource(
              makeRegistrarContact2()
                  .asBuilder()
                  .setAllowedToSetRegistryLockPassword(true)
                  .setRegistryLockPassword("hi")
                  .build());
          persistResource(makeRegistrar2().asBuilder().setRegistryLockAllowed(true).build());
          return null;
        });
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#contact-settings/johndoe@theregistrar.com"));
    Thread.sleep(1000);
    driver.waitForElement(By.tagName("h1"));
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    driver.diffPage("page");
  }

  @Test
  public void settingsContactEdit_setRegistryLockPassword_notAllowedForContact() throws Throwable {
    server.runInAppEngineEnvironment(
        () -> persistResource(makeRegistrar2().asBuilder().setRegistryLockAllowed(true).build()));
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#contact-settings/johndoe@theregistrar.com"));
    Thread.sleep(1000);
    driver.waitForElement(By.tagName("h1"));
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    driver.diffPage("page");
  }

  @Test
  public void settingsContactAdd() throws Throwable {
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#contact-settings"));
    Thread.sleep(1000);
    driver.waitForElement(By.tagName("h1"));
    driver.waitForElement(By.id("reg-app-btn-add")).click();
    driver.diffPage("page");
  }

  @Test
  public void settingsAdmin_whenAdmin() throws Throwable {
    server.setIsAdmin(true);
    driver.manage().window().setSize(new Dimension(1050, 2000));
    // To make sure we're only ADMIN (and not also "OWNER"), we switch to the NewRegistrar we
    // aren't in the contacts of
    driver.get(server.getUrl("/registrar?clientId=NewRegistrar#admin-settings"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("view");
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("edit");
  }

  /**
   * Makes sure the user can't "manually" enter the admin-settings.
   *
   * <p>Users don't have the "admin setting" tab (see the {@link #index_owner()} test). However, we
   * also want to make sure that if a user enter's the URL fragment manually they don't get the
   * admin page.
   *
   * <p>Note that failure here is a UI issue only and not a security issue, since any "admin" change
   * a user tries to do is validated on the backend and fails for non-admins.
   */
  @Test
  public void settingsAdmin_whenNotAdmin_showsHome() throws Throwable {
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#admin-settings"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("view");
  }

  @Test
  public void getOteStatus_noButtonWhenReal() throws Exception {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar#admin-settings"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("result");
  }

  @Test
  public void getOteStatus_notCompleted() throws Exception {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar?clientId=oteunfinished-1#admin-settings"));
    driver.findElement(By.id("btn-ote-status")).click();
    driver.findElement(By.id("ote-results-table")).click();
    // the 'hover' styling takes a bit to go away--sleep so we don't flake
    Thread.sleep(250);
    driver.diffPage("result");
  }

  @Test
  public void getOteStatus_completed() throws Exception {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar?clientId=otefinished-1#admin-settings"));
    driver.waitForElement(By.id("btn-ote-status"));
    driver.diffPage("before_click");
    driver.findElement(By.id("btn-ote-status")).click();
    driver.findElement(By.id("ote-results-table")).click();
    // the 'hover' styling takes a bit to go away--sleep so we don't flake
    Thread.sleep(250);
    driver.diffPage("result");
  }

  @Test
  public void settingsSecurity() throws Throwable {
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#security-settings"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("view");
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("edit");
  }

  /** Admins shouldn't have the "edit" button */
  @Test
  public void settingsSecurity_asAdmin() throws Throwable {
    server.setIsAdmin(true);
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar?clientId=NewRegistrar#security-settings"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("view");
  }

  @Test
  public void settingsSecurityWithCerts() throws Throwable {
    server.runInAppEngineEnvironment(
        () -> {
          persistResource(
              loadRegistrar("TheRegistrar")
                  .asBuilder()
                  .setClientCertificate(CertificateSamples.SAMPLE_CERT, START_OF_TIME)
                  .setFailoverClientCertificate(CertificateSamples.SAMPLE_CERT2, START_OF_TIME)
                  .build());
          return null;
        });
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#security-settings"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("view");
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("edit");
  }

  @Test
  public void settingsSecurityWithHashOnly() throws Throwable {
    server.runInAppEngineEnvironment(
        () -> {
          persistResource(
              loadRegistrar("TheRegistrar")
                  .asBuilder()
                  .setClientCertificateHash(CertificateSamples.SAMPLE_CERT_HASH)
                  .build());
          return null;
        });
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#security-settings"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("view");
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("edit");
  }

  @Test
  public void index_registrarDisabled() throws Throwable {
    server.runInAppEngineEnvironment(
        () ->
            persistResource(
                loadRegistrar("TheRegistrar").asBuilder().setState(State.DISABLED).build()));
    driver.get(server.getUrl("/registrar"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("view");
  }

  @Test
  public void settingsWhois() throws Throwable {
    driver.get(server.getUrl("/registrar#whois-settings"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("page");
  }

  @Test
  public void settingsWhoisEdit() throws Throwable {
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#whois-settings"));
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    Thread.sleep(1000);
    driver.diffPage("page");
  }

  @Test
  public void settingsWhoisEditError() throws Throwable {
    driver.manage().window().setSize(new Dimension(1050, 2000));
    driver.get(server.getUrl("/registrar#whois-settings"));
    driver.waitForElement(By.id("reg-app-btn-edit")).click();
    driver.setFormFieldsById(ImmutableMap.of("faxNumber", "cat"));
    driver.waitForElement(By.id("reg-app-btn-save")).click();
    Thread.sleep(1000);
    driver.diffPage("page");
  }

  @Test
  public void indexPage_smallScrolledDown() throws Throwable {
    driver.manage().window().setSize(new Dimension(600, 300));
    driver.get(server.getUrl("/registrar"));
    driver.waitForElement(By.tagName("h1"));
    driver.executeScript("document.getElementById('reg-content-and-footer').scrollTop = 200");
    Thread.sleep(500);
    driver.diffPage("page");
  }

  @Test
  public void registryLockVerify_success() throws Throwable {
    String lockVerificationCode = "f1be78a2-2d61-458c-80f0-9dd8f2f8625f";
    server.runInAppEngineEnvironment(
        () -> {
          createTld("tld");
          persistResource(newDomainBase("example.tld"));
          saveRegistryLock(
              new RegistryLock.Builder()
                  .setRegistrarPocId("johndoe@theregistrar.com")
                  .setRepoId("repoId")
                  .setRegistrarId("TheRegistrar")
                  .setVerificationCode("f1be78a2-2d61-458c-80f0-9dd8f2f8625f")
                  .isSuperuser(false)
                  .setDomainName("example.tld")
                  .build());
          return null;
        });
    driver.get(
        server.getUrl(
            "/registry-lock-verify?isLock=true&lockVerificationCode=" + lockVerificationCode));
    driver.waitForElement(By.id("reg-content"));
    driver.diffPage("page");
  }

  @Test
  public void registryLockVerify_unknownLock() throws Throwable {
    driver.get(server.getUrl("/registry-lock-verify?isLock=true&lockVerificationCode=asdfasdf"));
    driver.waitForElement(By.id("reg-content"));
    driver.diffPage("page");
  }

  @Test
  public void registryLock_empty() throws Throwable {
    driver.get(server.getUrl("/registrar?clientId=TheRegistrar#registry-lock"));
    driver.waitForElement(By.tagName("h2"));
    driver.diffPage("page");
  }

  @Test
  public void registryLock_notAllowed() throws Throwable {
    server.runInAppEngineEnvironment(
        () -> {
          persistResource(makeRegistrar2().asBuilder().setRegistryLockAllowed(false).build());
          return null;
        });
    driver.get(server.getUrl("/registrar?clientId=TheRegistrar#registry-lock"));
    driver.waitForElement(By.tagName("h2"));
    driver.diffPage("page");
  }

  @Test
  public void registryLock_nonEmpty() throws Throwable {
    server.runInAppEngineEnvironment(
        () -> {
          createDomainAndSaveLock();
          return null;
        });
    driver.get(server.getUrl("/registrar#registry-lock"));
    driver.waitForElement(By.tagName("h2"));
    driver.diffPage("page");
  }

  @Test
  public void registryLock_nonEmpty_admin() throws Throwable {
    server.runInAppEngineEnvironment(
        () -> {
          createTld("tld");
          // expired unlock request
          DomainBase expiredUnlockRequestDomain = persistActiveDomain("expiredunlock.tld");
          saveRegistryLock(
              createRegistryLock(expiredUnlockRequestDomain)
                  .asBuilder()
                  .setLockCompletionTimestamp(START_OF_TIME.minusDays(1))
                  .setUnlockRequestTimestamp(START_OF_TIME.minusDays(1))
                  .build());
          DomainBase domain = persistActiveDomain("example.tld");
          saveRegistryLock(createRegistryLock(domain).asBuilder().isSuperuser(true).build());
          DomainBase otherDomain = persistActiveDomain("otherexample.tld");
          saveRegistryLock(createRegistryLock(otherDomain));
          // include one pending-lock domain
          DomainBase pendingDomain = persistActiveDomain("pending.tld");
          saveRegistryLock(
              new RegistryLock.Builder()
                  .setVerificationCode(UUID.randomUUID().toString())
                  .isSuperuser(false)
                  .setRegistrarId("TheRegistrar")
                  .setRegistrarPocId("Marla.Singer@crr.com")
                  .setDomainName("pending.tld")
                  .setRepoId(pendingDomain.getRepoId())
                  .build());
          // and one pending-unlock domain
          DomainBase pendingUnlockDomain =
              persistResource(
                  newDomainBase("pendingunlock.tld")
                      .asBuilder()
                      .setStatusValues(REGISTRY_LOCK_STATUSES)
                      .build());
          saveRegistryLock(
              new RegistryLock.Builder()
                  .setVerificationCode(UUID.randomUUID().toString())
                  .isSuperuser(false)
                  .setRegistrarId("TheRegistrar")
                  .setRegistrarPocId("Marla.Singer@crr.com")
                  .setDomainName(pendingUnlockDomain.getDomainName())
                  .setRepoId(pendingUnlockDomain.getRepoId())
                  .setLockCompletionTimestamp(START_OF_TIME)
                  .setUnlockRequestTimestamp(START_OF_TIME)
                  .build());
          return null;
        });
    driver.get(server.getUrl("/registrar#registry-lock"));
    driver.waitForElement(By.tagName("h2"));
    driver.diffPage("page");
  }

  @Test
  public void registryLock_unlockModal() throws Throwable {
    server.runInAppEngineEnvironment(
        () -> {
          createDomainAndSaveLock();
          return null;
        });
    driver.get(server.getUrl("/registrar#registry-lock"));
    driver.waitForElement(By.tagName("h2"));
    driver.findElement(By.id("button-unlock-example.tld")).click();
    driver.waitForElement(By.className("modal-content"));
    driver.findElement(By.id("domain-lock-password")).sendKeys("password");
    driver.diffPage("page");
  }

  @Test
  public void registryLock_lockModal() throws Throwable {
    server.setIsAdmin(true);
    server.runInAppEngineEnvironment(
        () -> {
          createTld("tld");
          persistActiveDomain("example.tld");
          return null;
        });
    driver.get(server.getUrl("/registrar#registry-lock"));
    driver.waitForElement(By.tagName("h2"));
    driver.findElement(By.id("button-lock-domain")).click();
    driver.waitForElement(By.className("modal-content"));
    driver.findElement(By.id("domain-lock-input-value")).sendKeys("somedomain.tld");
    driver.diffPage("page");
  }

  @Test
  public void registryLock_notAllowedForUser() throws Throwable {
    server.runInAppEngineEnvironment(
        () -> {
          persistResource(
              AppEngineRule.makeRegistrarContact3()
                  .asBuilder()
                  .setAllowedToSetRegistryLockPassword(true)
                  .build());
          return null;
        });
    driver.get(server.getUrl("/registrar?clientId=TheRegistrar#registry-lock"));
    driver.waitForElement(By.tagName("h2"));
    driver.diffPage("page");
  }

  private void createDomainAndSaveLock() {
    createTld("tld");
    DomainBase domainBase = persistActiveDomain("example.tld");
    saveRegistryLock(createRegistryLock(domainBase));
  }

  private RegistryLock createRegistryLock(DomainBase domainBase) {
    return new RegistryLock.Builder()
        .setVerificationCode(UUID.randomUUID().toString())
        .isSuperuser(false)
        .setRegistrarId("TheRegistrar")
        .setRegistrarPocId("Marla.Singer@crr.com")
        .setLockCompletionTimestamp(START_OF_TIME)
        .setDomainName(domainBase.getDomainName())
        .setRepoId(domainBase.getRepoId())
        .build();
  }
}
