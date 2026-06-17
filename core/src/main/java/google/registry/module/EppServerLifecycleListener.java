// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.module;

import com.google.common.flogger.FluentLogger;
import google.registry.eppserver.DaggerEppServerModule_EppServerComponent;
import google.registry.eppserver.EppServer;
import google.registry.eppserver.EppServerModule;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/** A {@link ServletContextListener} that starts and stops the integrated Netty EPP server. */
@WebListener
public class EppServerLifecycleListener implements ServletContextListener {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private EppServer eppServer;
  private EppServerModule.EppServerComponent eppServerComponent;

  @Override
  public void contextInitialized(ServletContextEvent sce) {
    if (!Boolean.parseBoolean(System.getenv("TCP_SERVER_ENABLED"))) {
      logger.atInfo().log(
          "TCP_SERVER_ENABLED is false or not set. Skipping integrated EPP server initialization.");
      return;
    }

    logger.atInfo().log("Initializing integrated EPP server...");
    try {
      EppServerModule eppServerModule = new EppServerModule();
      eppServerComponent =
          DaggerEppServerModule_EppServerComponent.builder()
              .eppServerModule(eppServerModule)
              .build();

      eppServer = new EppServer(eppServerComponent);
      eppServer.start();
      logger.atInfo().log("Integrated EPP server started successfully.");
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Failed to start integrated EPP server.");
    }
  }

  @Override
  public void contextDestroyed(ServletContextEvent sce) {
    if (eppServer != null) {
      logger.atInfo().log("Stopping integrated EPP server...");
      eppServer.stop();
      logger.atInfo().log("Integrated EPP server stopped.");
    }
    if (eppServerComponent != null) {
      eppServerComponent
          .jedis()
          .ifPresent(
              jedis -> {
                try {
                  jedis.close();
                  logger.atInfo().log("Closed UnifiedJedis client.");
                } catch (Exception e) {
                  logger.atSevere().withCause(e).log("Failed to close UnifiedJedis client.");
                }
              });
    }
  }
}
