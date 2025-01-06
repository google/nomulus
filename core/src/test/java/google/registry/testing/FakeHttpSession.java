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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkState;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/** A fake {@link HttpSession} that only provides support for getting/setting attributes. */
@SuppressWarnings("deprecation")
public class FakeHttpSession implements HttpSession {

  private final Map<String, Object> map = new HashMap<>();

  boolean isValid = true;

  @Override
  public long getCreationTime() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getLastAccessedTime() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ServletContext getServletContext() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setMaxInactiveInterval(int interval) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getMaxInactiveInterval() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object getAttribute(@Nullable String name) {
    checkState(isValid, "This session has been invalidated.");
    return map.get(name);
  }

  @Override
  public Enumeration<String> getAttributeNames() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setAttribute(@Nullable String name, @Nullable Object value) {
    checkState(isValid, "This session has been invalidated.");
    map.put(name, value);
  }

  @Override
  public void removeAttribute(@Nullable String name) {
    checkState(isValid, "This session has been invalidated.");
    map.remove(name);
  }

  @Override
  public void invalidate() {
    isValid = false;
    map.clear();
  }

  @Override
  public boolean isNew() {
    throw new UnsupportedOperationException();
  }
}
