// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.request;

import static com.google.common.base.Preconditions.checkArgument;

/** Helper methods for working with {@link Action @Action}-annotated classes. */
public final class Actions {

  /** Extracts the action path from the {@link Action @Action} annotation. */
  public static String getPathForAction(Class<?> action) {
    checkArgument(
        action.isAnnotationPresent(Action.class),
        "Cannot get path of class without @Action annotation: %s", action);
    return action.getAnnotation(Action.class).path();
  }

  private Actions() {}
}
