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

package google.registry.server;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static google.registry.util.TypeUtils.instantiate;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javax.annotation.Nullable;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet that wraps a servlet and delegates request execution to a queue.
 *
 * <p>The actual invocation of the delegate does not happen within this servlet's lifecycle.
 * Therefore, the task on the queue must manually invoke filters within the queue task.
 *
 * @see TestServer
 */
public final class ServletWrapperDelegatorServlet extends HttpServlet {

  private final Queue<FutureTask<Void>> requestQueue;
  private final Class<? extends HttpServlet> servletClass;
  private final ImmutableList<Class<? extends Filter>> filterClasses;

  ServletWrapperDelegatorServlet(
      Class<? extends HttpServlet> servletClass,
      ImmutableList<Class<? extends Filter>> filterClasses,
      Queue<FutureTask<Void>> requestQueue) {
    this.servletClass = servletClass;
    this.filterClasses = filterClasses;
    this.requestQueue = checkNotNull(requestQueue, "requestQueue");
  }

  @Override
  public void service(final HttpServletRequest req, final HttpServletResponse rsp)
      throws ServletException, IOException {
    FutureTask<Void> task = new FutureTask<>(new Callable<Void>() {
      @Nullable
      @Override
      public Void call() throws ServletException, IOException {
        // Simulate the full filter chain with the servlet at the end.
        final Iterator<Class<? extends Filter>> filtersIter = filterClasses.iterator();
        FilterChain filterChain =
            new FilterChain() {
              @Override
              public void doFilter(ServletRequest request, ServletResponse response)
                  throws IOException, ServletException {
                if (filtersIter.hasNext()) {
                  instantiate(filtersIter.next()).doFilter(request, response, this);
                } else {
                  instantiate(servletClass).service(request, response);
                }
              }};
        filterChain.doFilter(req, rsp);
        return null;
      }});
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
