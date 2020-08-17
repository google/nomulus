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

import org.openqa.selenium.WebElement;

/** Interface to provide APIs to compare screenshots for visual regression tests. */
interface ScreenDiffer {

  /**
   * Checks that the screenshot of the element matches the golden image by pixel comparison. {@link
   * #verifyAndQuit()} needs to be invoked to actually trigger the comparison.
   *
   * <p>On mismatch, the test will continue until the end of the test (and then fail). This is so
   * other screenshot matches can be executed in the same function - allowing you to approve /
   * reject all at once.
   *
   * @param element the element on the page to be compared
   * @param imageKey a unique name such that by prepending the calling class name and method name in
   *     the format of ClassName_MethodName_<imageKey> will uniquely identify golden image.
   */
  void diffElement(WebElement element, String imageKey);

  /**
   * Checks that the screenshot matches the golden image by pixel comparison. {@link
   * #verifyAndQuit()} needs to be invoked to actually trigger the comparison.
   *
   * <p>On mismatch, the test will continue until the end of the test (and then fail). This is so
   * other screenshot matches can be executed in the same function - allowing you to approve /
   * reject all at once.
   *
   * @param imageKey a unique name such that by prepending the calling class name and method name in
   *     the format of ClassName_MethodName_<imageKey> will uniquely identify golden image.
   */
  void diffPage(String imageKey);

  /** Asserts that all diffs up to this point have PASSED. */
  void verifyAndQuit();
}
