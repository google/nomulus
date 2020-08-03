// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;

import google.registry.util.UrlChecker;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** JUnit extension for managing Docker containers used by WebDriver tests. */
class DockerWebDriverExtension implements BeforeAllCallback, AfterAllCallback {

  // This port number is defined in this Dockerfile:
  // https://github.com/SeleniumHQ/docker-selenium/blob/master/StandaloneChrome/Dockerfile#L21
  private static final int CHROME_DRIVER_SERVICE_PORT = 4444;
  private static final URL WEB_DRIVER_URL = getWebDriverUrl();
  private WebDriver webDriver;

  private static URL getWebDriverUrl() {
    // TODO(#209): Find a way to automatically detect the version of docker image
    GenericContainer container =
        new GenericContainer("selenium/standalone-chrome:3.141.59-mercury")
            .withFileSystemBind("/dev/shm", "/dev/shm", BindMode.READ_WRITE)
            .withExposedPorts(CHROME_DRIVER_SERVICE_PORT)
            .waitingFor(Wait.forHttp("/").withStartupTimeout(Duration.of(20, ChronoUnit.SECONDS)));
    container.start();
    URL url;
    try {
      url =
          new URL(
              String.format(
                  "http://%s:%d/wd/hub",
                  container.getContainerIpAddress(),
                  container.getMappedPort(CHROME_DRIVER_SERVICE_PORT)));
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException(e);
    }
    UrlChecker.waitUntilAvailable(url, 20000);
    return url;
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    ChromeOptions chromeOptions = new ChromeOptions().setHeadless(true);
    webDriver = new RemoteWebDriver(WEB_DRIVER_URL, chromeOptions);
  }

  @Override
  public void afterAll(ExtensionContext context) {
    try {
      webDriver.quit();
    } finally {
      webDriver = null;
    }
  }

  /**
   * Returns {@link WebDriver} instance connected to the {@link
   * org.openqa.selenium.chrome.ChromeDriverService} running in the container.
   */
  WebDriver getWebDriver() {
    return checkNotNull(webDriver);
  }
}
