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

import static com.google.common.io.Resources.getResource;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.text.StringEscapeUtils.escapeEcmaScript;

import com.google.common.base.Preconditions;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.DesiredCapabilities;

/**
 * WebDriver delegate JUnit extension that exposes most of the {@link WebDriver} API and the {@link
 * ScreenDiffer} API.
 */
@SuppressWarnings("deprecation")
public final class WebDriverPlusScreenDifferExtension
    implements BeforeEachCallback,
        AfterEachCallback,
        WebDriver,
        org.openqa.selenium.interactions.HasInputDevices,
        TakesScreenshot,
        JavascriptExecutor,
        HasCapabilities {

  private static final int WAIT_FOR_ELEMENTS_POLLING_INTERVAL_MS = 10;
  private static final int WAIT_FOR_ELEMENTS_BONUS_DELAY_MS = 150;

  // The maximum difference between pixels that would be considered as "identical". Calculated as
  // the sum of the absolute difference between the values of the RGB channels. So a 120,30,200
  // reference pixel and a 122,31,193 result pixel have a difference of 10, and would be considered
  // identical if MAX_COLOR_DIFF=10
  private static final int MAX_COLOR_DIFF = 20;

  // Percent of pixels that are allowed to be different (more than the MAX_COLOR_DIFF) between the
  // images while still considering the test to have passed. Useful if there are a very small
  // number of pixels that vary (usually on the corners of "rounded" buttons or similar)
  private static final int MAX_PIXEL_DIFF = 0;

  // Default size of the browser window when taking screenshot. Having a fixed size of window can
  // help make visual regression test deterministic.
  private static final Dimension DEFAULT_WINDOW_SIZE = new Dimension(1200, 2000);

  private static final String GOLDENS_PATH =
      getResource(WebDriverPlusScreenDifferExtension.class, "goldens/chrome-linux").getFile();

  private WebDriverProvider webDriverProvider;
  private WebDriver driver;
  private ScreenDiffer webDriverPlusScreenDiffer;

  // Prefix to use for golden image files, will be set to ClassName_MethodName once the test
  // starts. Will be added a user-given imageKey as a suffix, and of course a '.png' at the end.
  private String imageNamePrefix = null;

  @FunctionalInterface
  public interface WebDriverProvider {
    WebDriver getWebDriver();
  }

  /** Constructs a {@link WebDriverPlusScreenDifferExtension} instance. */
  WebDriverPlusScreenDifferExtension(WebDriverProvider webDriverProvider) {
    this.webDriverProvider = webDriverProvider;
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    driver = webDriverProvider.getWebDriver();
    webDriverPlusScreenDiffer =
        new WebDriverScreenDiffer(driver, GOLDENS_PATH, MAX_COLOR_DIFF, MAX_PIXEL_DIFF);
    // non-zero timeout so findByElement will wait for the element to appear
    driver.manage().timeouts().implicitlyWait(5, TimeUnit.SECONDS);
    driver.manage().window().setSize(DEFAULT_WINDOW_SIZE);

    if (imageNamePrefix == null) {
      String className = context.getRequiredTestClass().getSimpleName();
      String methodName = context.getRequiredTestMethod().getName();
      String unsanitizedName = className + "_" + methodName;
      // remove all of the special wildcard characters so they don't exist in filenames.
      imageNamePrefix = unsanitizedName.replaceAll("[*?~\"\\[\\]]", "");
    }
  }

  @Override
  public void afterEach(ExtensionContext context) {
    webDriverPlusScreenDiffer.verifyAndQuit();
  }

  /** @see #get(String) */
  public void get(URL url) {
    driver.get(url.toString());
  }

  /** Waits indefinitely for an element to appear on the page, then returns it. */
  WebElement waitForElement(By by) throws InterruptedException {
    while (true) {
      List<WebElement> elements = findElements(by);
      if (!elements.isEmpty()) {
        Thread.sleep(WAIT_FOR_ELEMENTS_BONUS_DELAY_MS);
        return elements.get(0);
      }
      Thread.sleep(WAIT_FOR_ELEMENTS_POLLING_INTERVAL_MS);
    }
  }

  /** Waits for element matching {@code by} whose {@code attribute} satisfies {@code predicate}. */
  public WebElement waitForAttribute(
      By by, String attribute, Predicate</*@Nullable*/ ? super CharSequence> predicate)
      throws InterruptedException {
    while (true) {
      for (WebElement element : findElements(by)) {
        if (predicate.test(element.getAttribute(attribute))) {
          Thread.sleep(WAIT_FOR_ELEMENTS_BONUS_DELAY_MS);
          return element;
        }
      }
      Thread.sleep(WAIT_FOR_ELEMENTS_POLLING_INTERVAL_MS);
    }
  }

  /** Sets value of input fields, where {@code fields} key is the {@code id=""} attribute. */
  void setFormFieldsById(Map<String, String> fields) {
    executeScript(
        fields.entrySet().stream()
            .map(
                entry ->
                    String.format(
                        "document.getElementById('%s').value = '%s';",
                        escapeEcmaScript(entry.getKey()), escapeEcmaScript(entry.getValue())))
            .collect(joining("\n")));
  }

  /**
   * Checks that the screenshot of the element matches the golden image by pixel comparison.
   *
   * <p>On mismatch, the test will continue until the end of the test (and then fail). This is so
   * other screenshot matches can be executed in the same function - allowing you to approve /
   * reject all at once.
   *
   * @param imageKey a unique name such that by prepending the calling class name and method name in
   *     the format of ClassName_MethodName_<imageKey> will uniquely identify golden image.
   * @param element the element on the page to be compared
   */
  private void diffElement(String imageKey, WebElement element) {
    webDriverPlusScreenDiffer.diffElement(element, getUniqueName(imageKey));
  }

  /**
   * Checks that the screenshot of the element matches the golden image by pixel comparison.
   *
   * <p>On mismatch, the test will continue until the end of the test (and then fail). This is so
   * other screenshot matches can be executed in the same function - allowing you to approve /
   * reject all at once.
   *
   * @param imageKey a unique name such that by prepending the calling class name and method name in
   *     the format of ClassName_MethodName_<imageKey> will uniquely identify golden image.
   * @param by {@link By} which locates the element on the page to be compared
   */
  public void diffElement(String imageKey, By by) {
    diffElement(imageKey, driver.findElement(by));
  }

  /**
   * Checks that the screenshot matches the golden image by pixel comparison.
   *
   * <p>On mismatch, the test will continue until the end of the test (and then fail). This is so
   * other screenshot matches can be executed in the same function - allowing you to approve /
   * reject all at once.
   *
   * @param imageKey a unique name such that by prepending the calling class name and method name in
   *     the format of ClassName_MethodName_<imageKey> will uniquely identify golden image.
   */
  void diffPage(String imageKey) {
    webDriverPlusScreenDiffer.diffPage(getUniqueName(imageKey));
  }

  @Override
  public void get(String url) {
    driver.get(url);
  }

  @Override
  public String getCurrentUrl() {
    return driver.getCurrentUrl();
  }

  @Override
  public String getTitle() {
    return driver.getTitle();
  }

  @Override
  public List<WebElement> findElements(By by) {
    return driver.findElements(by);
  }

  @Override
  public WebElement findElement(By by) {
    return driver.findElement(by);
  }

  @Override
  public String getPageSource() {
    return driver.getPageSource();
  }

  @Override
  public void close() {
    driver.close();
  }

  @Override
  public void quit() {
    driver.quit();
  }

  @Override
  public Set<String> getWindowHandles() {
    return driver.getWindowHandles();
  }

  @Override
  public String getWindowHandle() {
    return driver.getWindowHandle();
  }

  @Override
  public TargetLocator switchTo() {
    return driver.switchTo();
  }

  @Override
  public Navigation navigate() {
    return driver.navigate();
  }

  @Override
  public Options manage() {
    return driver.manage();
  }

  @Override
  public org.openqa.selenium.interactions.Keyboard getKeyboard() {
    return ((org.openqa.selenium.interactions.HasInputDevices) driver).getKeyboard();
  }

  @Override
  public org.openqa.selenium.interactions.Mouse getMouse() {
    return ((org.openqa.selenium.interactions.HasInputDevices) driver).getMouse();
  }

  @Override
  public <X> X getScreenshotAs(OutputType<X> target) throws WebDriverException {
    return ((TakesScreenshot) driver).getScreenshotAs(target);
  }

  @Override
  public Object executeScript(String script, Object... args) {
    return ((JavascriptExecutor) driver).executeScript(script, args);
  }

  @Override
  public Object executeAsyncScript(String script, Object... args) {
    return ((JavascriptExecutor) driver).executeAsyncScript(script, args);
  }

  @Override
  public Capabilities getCapabilities() {
    return new DesiredCapabilities();
  }

  private String getUniqueName(String imageKey) {
    Preconditions.checkNotNull(imageNamePrefix);
    return imageNamePrefix + "_" + imageKey;
  }
}
