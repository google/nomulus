package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action.GkeService;
import java.io.IOException;
import java.util.List;
import javax.inject.Inject;

/** Command to manually perform an authenticated RDAP query. */
@Parameters(separators = " =", commandDescription = "Manually perform an authenticated RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final ImmutableSet<String> VALID_TYPES =
      ImmutableSet.of("domain", "registrar", "contact", "nameserver");

  @Parameter(description = "The object type and query term.", required = true)
  private List<String> mainParameters;

  private ServiceConnection defaultConnection;

  @Inject
  @Config("useCanary")
  boolean useCanary;

  @Override
  public void setConnection(ServiceConnection connection) {
    this.defaultConnection = connection;
  }

  @Override
  public void run() {
    checkArgument(
        mainParameters != null && mainParameters.size() == 2,
        "Usage: nomulus rdap_query <type> <query_term>\n"
            + "  <type> must be one of "
            + VALID_TYPES);

    String type = Ascii.toLowerCase(mainParameters.get(0));
    checkArgument(
        VALID_TYPES.contains(type),
        "Invalid object type '%s'. Must be one of %s",
        type,
        VALID_TYPES);

    String name = mainParameters.get(1);
    String path = String.format("/rdap/%s/%s", type, name);

    logger.atInfo().log("Starting RDAP query for path: %s", path);

    try {
      if (defaultConnection == null) {
        throw new IllegalStateException("ServiceConnection was not set by RegistryCli.");
      }
      // Create a new ServiceConnection instance targeting GkeService.PUBAPI.
      // This is necessary because the default connection provided by RegistryCli
      // targets GkeService.BACKEND, but RDAP queries need to be routed to the
      // public-facing API.
      ServiceConnection pubapiConnection =
          defaultConnection.withService(GkeService.PUBAPI, useCanary);

      String rdapResponse = pubapiConnection.sendGetRequest(path, ImmutableMap.of());
      JsonElement rdapJson = JsonParser.parseString(rdapResponse);

      // Pretty-print the JSON response.
      Gson gson = new GsonBuilder().setPrettyPrinting().create();
      System.out.println(gson.toJson(rdapJson));

      logger.atInfo().log("Successfully completed RDAP query for path: %s", path);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Request failed for path: %s", path);
      System.err.println("Request failed for " + path + ": " + e.getMessage());
    }
  }
}
