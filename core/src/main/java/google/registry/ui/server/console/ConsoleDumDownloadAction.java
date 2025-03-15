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

package google.registry.ui.server.console;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.GkeService;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.joda.time.DateTime;

@Action(
    service = GaeService.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = ConsoleDumDownloadAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleDumDownloadAction extends ConsoleApiAction {

  private static final String SQL_TEMPLATE =
      """
            SELECT CONCAT(
              d.domain_name,',',d.creation_time,',',d.registration_expiration_time,',',d.statuses
            ) AS result FROM "Domain" d
            WHERE d.current_sponsor_registrar_id = :registrarId
            AND d.deletion_time > ':now'
            AND d.creation_time <= ':now';
      """;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PATH = "/console-api/dum-download";
  private final Clock clock;
  private final String registrarId;
  private final String dumFileName;

  @Inject
  public ConsoleDumDownloadAction(
      Clock clock,
      ConsoleApiParams consoleApiParams,
      @Parameter("registrarId") String registrarId,
      @Config("dumFileName") String dumFileName) {
    super(consoleApiParams);
    this.registrarId = registrarId;
    this.clock = clock;
    this.dumFileName = dumFileName;
  }

  @Override
  protected void getHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.DOWNLOAD_DOMAINS);
    consoleApiParams.response().setContentType(MediaType.CSV_UTF_8);
    consoleApiParams
        .response()
        .setHeader(
            "Content-Disposition", String.format("attachment; filename=%s.csv", dumFileName));
    consoleApiParams
        .response()
        .setHeader("Cache-Control", "max-age=86400"); // 86400 seconds = 1 day
    consoleApiParams
        .response()
        .setDateHeader("Expires", DateTime.now(UTC).withTimeAtStartOfDay().plusDays(1));

    try (var writer = consoleApiParams.response().getWriter()) {
      CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT);
      writeCsv(csvPrinter);
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          String.format("Failed to create DUM csv for %s", registrarId));
      consoleApiParams.response().setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return;
    }
    consoleApiParams.response().setStatus(HttpServletResponse.SC_OK);
  }

  private void writeCsv(CSVPrinter printer) throws IOException {
    String sql = SQL_TEMPLATE.replaceAll(":now", clock.nowUtc().toString());

    // We deliberately don't want to use ImmutableList.copyOf because underlying list may contain
    // large amount of records and that will degrade performance.
    List<String> queryResult =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery(sql)
                        .setParameter("registrarId", registrarId)
                        .setHint("org.hibernate.fetchSize", 1000)
                        .getResultList());

    ImmutableList<String[]> formattedRecords =
        queryResult.stream().map(r -> r.split(",")).collect(toImmutableList());
    printer.printRecord(
        ImmutableList.of("Domain Name", "Creation Time", "Expiration Time", "Domain Statuses"));
    printer.printRecords(formattedRecords);
  }
}
