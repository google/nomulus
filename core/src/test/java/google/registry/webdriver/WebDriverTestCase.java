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

import google.registry.webdriver.RepeatableRunner.AttemptNumber;
import org.junit.ClassRule;
import org.junit.Rule;

/** Base class for tests that needs a {@link WebDriverPlusScreenDifferRule}. */
public class WebDriverTestCase {
  @ClassRule
  public static final DockerWebDriverRule webDriverProvider = new DockerWebDriverRule();
  public final AttemptNumber attemptNumber = new AttemptNumber();

  @Rule
  public final WebDriverPlusScreenDifferRule driver =
      new WebDriverPlusScreenDifferRule(webDriverProvider::getWebDriver, attemptNumber);
}
