package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/** Command to execute an authenticated RDAP query. */
@Parameters(separators = " =", commandDescription = "Manually perform an authenticated RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  private static final ImmutableSet<String> VALID_TYPES =
      ImmutableSet.of("domain", "nameserver", "entity");

  @Parameter(
      description = "RDAP query string, in the format <type> <name>",
      required = true)
  private List<String> mainParameters;

  private Connection connection;

  @Override
  public void setConnection(Connection connection) {
    this.connection = connection;
  }

  @Override
  public void run() throws Exception {
    if (mainParameters.size() != 2) {
      throw new IllegalArgumentException("Expected 2 arguments: <type> <name>");
    }
    String type = mainParameters.get(0).toLowerCase();
    if (!VALID_TYPES.contains(type)) {
      throw new IllegalArgumentException(
          String.format("Invalid object type '%s'. Must be one of %s", type, VALID_TYPES));
    }
    String name = mainParameters.get(1);
    String path = String.format("/rdap/%s/%s", type, name);

    // Use the connection object to make an authenticated GET request.
    String rdapResponse = connection.sendGetRequest(path);

    // Parse and format the JSON response.
    JsonElement rdapJson = new Gson().fromJson(rdapResponse, JsonElement.class);
    System.out.println(formatJson(rdapJson, ""));
  }

  /**
   * Recursively formats a JSON object into indented key-value pairs.
   */
  private String formatJson(JsonElement jsonElement, String indent) {
    StringBuilder sb = new StringBuilder();
    if (jsonElement.isJsonObject()) {
      for (Map.Entry<String, JsonElement> entry : jsonElement.getAsJsonObject().entrySet()) {
        sb.append(indent).append(entry.getKey()).append(":\n");
        sb.append(formatJson(entry.getValue(), indent + "  "));
      }
    } else if (jsonElement.isJsonArray()) {
      for (JsonElement element : jsonElement.getAsJsonArray()) {
        sb.append(formatJson(element, indent + "- "));
      }
    } else if (jsonElement.isJsonPrimitive()) {
      sb.append(indent).append(jsonElement.getAsString()).append("\n");
    }
    return sb.toString();
  }
}
