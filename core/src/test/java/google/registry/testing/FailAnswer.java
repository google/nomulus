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

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Display helpful failure message if a mocked method is called.
 *
 * <p>One important problem this solves is when you mock servlets and the test fails, you usually
 * end up with failure messages like {@code Wanted but not invoked: rsp.setStatus(200)} which aren't
 * very helpful. This is because servlets normally report problems by calling {@link
 * jakarta.servlet.http.HttpServletResponse#sendError(int, String) rsp.sendError()} so it'd be nice
 * if we could have the error message be whatever arguments get passed to {@code sendError}.
 *
 * <p>And that's where {@link FailAnswer} comes to the rescue! Here's an example of what you could
 * put at the beginning of a servlet test method to have better error messages:
 *
 * <pre>{@code
 * doAnswer(new FailAnswer<>()).when(rsp).sendError(anyInt());
 * doAnswer(new FailAnswer<>()).when(rsp).sendError(anyInt(), anyString());
 *
 * }</pre>
 *
 * @param <T> The return type of the mocked method (which doesn't actually return).
 */
public class FailAnswer<T> implements Answer<T> {

  @Override
  public T answer(@SuppressWarnings("null") InvocationOnMock args) {
    StringBuilder msg = new StringBuilder();
    boolean first = true;
    for (Object arg : args.getArguments()) {
      if (first) {
        first = false;
      } else {
        msg.append(", ");
      }
      msg.append(arg);
    }
    throw new AssertionError(msg.toString());
  }
}
