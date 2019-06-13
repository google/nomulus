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

package google.registry.request;

import google.registry.request.auth.Auth;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Annotation for {@link Runnable} actions accepting HTTP requests from {@link RequestHandler}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Action {

  /** HTTP methods recognized by the request processor. */
  enum Method { GET, HEAD, POST }

  /** App Engine services supported by the request processor. */
  enum Service {
    DEFAULT("default"),
    TOOLS("tools"),
    BACKEND("backend"),
    PUBAPI("pubapi");

    private final String serviceId;

    Service(String serviceId) {
      this.serviceId = serviceId;
    }

    /** Returns the actual service id in App Engine. */
    public String getServiceId() {
      return serviceId;
    }
  }

  /** Which App Engine service this action lives on. */
  Service service();

  /** HTTP path to serve the action from. The path components must be percent-escaped. */
  String path();

  /** Indicates all paths starting with this path should be accepted. */
  boolean isPrefix() default false;

  /** HTTP methods that request processor should allow. */
  Method[] method() default Method.GET;

  /**
   * Indicates request processor should print "OK" to the HTTP client on success.
   *
   * <p>This is important because it's confusing to manually invoke a backend task and have a blank
   * page show up. And it's not worth injecting a {@link Response} object just to do something so
   * trivial.
   */
  boolean automaticallyPrintOk() default false;

  /** Authentication settings. */
  Auth auth();
}
