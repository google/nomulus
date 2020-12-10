// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.privileges.secretmanager;

import static avro.shaded.com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import java.util.List;

/**
 * Contains the login name and password of a Cloud SQL user.
 *
 * <p>User must take care not to include the {@link #SEPARATOR} in property values.
 */
@AutoValue
public abstract class SqlCredential {

  public static final Character SEPARATOR = ' ';

  public abstract String login();

  public abstract String password();

  @Override
  public final String toString() {
    // Use Object.toString(), which does not show object data.
    return super.toString();
  }

  public final String toFormattedString() {
    return String.format("%s%c%s", login(), SEPARATOR, password());
  }

  public static SqlCredential fromFormattedString(String sqlCredential) {
    List<String> items = com.google.common.base.Splitter.on(SEPARATOR).splitToList(sqlCredential);
    checkState(items.size() == 2, "Invalid SqlCredential string.");
    return of(items.get(0), items.get(1));
  }

  public static SqlCredential of(String login, String password) {
    return new AutoValue_SqlCredential(login, password);
  }
}
