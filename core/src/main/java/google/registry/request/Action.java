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

import static com.google.common.base.Preconditions.checkState;

import google.registry.config.RegistryConfig;
import google.registry.request.auth.Auth;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URL;

/** Annotation for {@link Runnable} actions accepting HTTP requests from {@link RequestHandler}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Action {

  /** HTTP methods recognized by the request processor. */
  enum Method {
    GET,
    HEAD,
    POST,
    DELETE
  }

  interface Service {
    String getServiceId();

    URL getServiceUrl();
  }

  enum GaeService implements Service {
    BSA("bsa"),
    DEFAULT("default"),
    TOOLS("tools"),
    BACKEND("backend"),
    PUBAPI("pubapi");

    private final String serviceId;

    GaeService(String serviceId) {
      this.serviceId = serviceId;
    }

    @Override
    public String getServiceId() {
      return serviceId;
    }

    @Override
    public URL getServiceUrl() {
      return switch (this) {
        case DEFAULT -> RegistryConfig.getDefaultServer();
        case TOOLS -> RegistryConfig.getToolsServer();
        case BACKEND -> RegistryConfig.getBackendServer();
        case BSA -> RegistryConfig.getBsaServer();
        case PUBAPI -> RegistryConfig.getPubapiServer();
      };
    }
  }

  enum GkeService implements Service {
    // This designation means that it defers to the GAE service, so we don't have to annotate EVERY
    // action during the GKE migration.
    SAME_AS_GAE("same_as_gae"),
    FRONTEND("frontend"),
    BACKEND("backend"),
    PUBAPI("pubapi"),
    CONSOLE("console");

    private final String serviceId;

    GkeService(String serviceId) {
      this.serviceId = serviceId;
    }

    @Override
    public String getServiceId() {
      checkState(this != SAME_AS_GAE, "Cannot get service Id for SAME_AS_GAE");
      return serviceId;
    }

    @Override
    public URL getServiceUrl() {
      return RegistryConfig.getServiceUrl(this);
    }
  }

  /** Which App Engine service this action lives on. */
  GaeService service();

  /** Which GKE service this action lives on. */
  GkeService gkeService() default GkeService.SAME_AS_GAE;

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

  // TODO(jianglai): Use Action.gkeService() directly once we are off GAE.
  class ServiceGetter {
    public static GkeService get(Action action) {
      GkeService service = action.gkeService();
      if (service != GkeService.SAME_AS_GAE) {
        return service;
      }
      GaeService gaeService = action.service();
      return switch (gaeService) {
        case DEFAULT -> GkeService.FRONTEND;
        case BACKEND -> GkeService.BACKEND;
        case TOOLS -> GkeService.BACKEND;
        case BSA -> GkeService.BACKEND;
        case PUBAPI -> GkeService.PUBAPI;
      };
    }
  }
}
