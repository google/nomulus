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

package google.registry.server;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static google.registry.util.TypeUtils.instantiate;

import com.google.common.util.concurrent.Uninterruptibles;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javax.annotation.Nullable;

/**
 * Servlet that wraps a servlet and delegates request execution to a queue.
 *
 * @see TestServer
 */
public final class ServletWrapperDelegatorServlet extends HttpServlet {

  private final Queue<FutureTask<Void>> requestQueue;
  private final Class<? extends HttpServlet> servletClass;

  ServletWrapperDelegatorServlet(
      Class<? extends HttpServlet> servletClass,
      Queue<FutureTask<Void>> requestQueue) {
    this.servletClass = servletClass;
    this.requestQueue = checkNotNull(requestQueue, "requestQueue");
  }

  @Override
  public void service(final HttpServletRequest req, final HttpServletResponse rsp)
      throws ServletException, IOException {
    FutureTask<Void> task =
        new FutureTask<>(
            new Callable<>() {
              @Nullable
              @Override
              public Void call() throws ServletException, IOException {
                instantiate(servletClass).service(req, rsp);
                return null;
              }
            });
    requestQueue.add(task);
    try {
      Uninterruptibles.getUninterruptibly(task);
    } catch (ExecutionException e) {
      throwIfInstanceOf(e.getCause(), ServletException.class);
      throwIfInstanceOf(e.getCause(), IOException.class);
      throw new RuntimeException(e.getCause());
    }
  }
}
