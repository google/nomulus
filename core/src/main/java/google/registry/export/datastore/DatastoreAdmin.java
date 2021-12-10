// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.export.datastore;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.client.googleapis.services.json.AbstractGoogleJsonClient;
import com.google.api.client.googleapis.services.json.AbstractGoogleJsonClientRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.GenericJson;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.util.Key;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import google.registry.model.annotations.DeleteAfterMigration;
import java.util.Collection;
import java.util.Optional;

/**
 * Java client to <a href="https://cloud.google.com/datastore/docs/reference/admin/rest/">Cloud
 * Datastore Admin REST API</a>.
 */
@DeleteAfterMigration
public class DatastoreAdmin extends AbstractGoogleJsonClient {

  private static final String ROOT_URL = "https://datastore.googleapis.com/v1/";
  private static final String SERVICE_PATH = "";

  // GCP project that this instance is associated with.
  private final String projectId;

  protected DatastoreAdmin(Builder builder) {
    super(builder);
    this.projectId = checkNotNull(builder.projectId, "GCP projectId missing.");
  }

  /**
   * Returns an {@link Export} request that starts exporting all Cloud Datastore databases owned by
   * the GCP project identified by {@link #projectId}.
   *
   * <p>Typical usage is:
   *
   * <pre>
   *     {@code Export export = datastoreAdmin.export(parameters ...);}
   *     {@code Operation operation = export.execute();}
   *     {@code while (!operation.isSuccessful()) { ...}}
   * </pre>
   *
   * <p>Please see the <a
   * href="https://cloud.google.com/datastore/docs/reference/admin/rest/v1/projects/export">API
   * specification of the export method for details</a>.
   *
   * <p>The following undocumented behaviors with regard to {@code outputUrlPrefix} have been
   * observed:
   *
   * <ul>
   *   <li>If outputUrlPrefix refers to a GCS bucket, exported data will be nested deeper in the
   *       bucket with a timestamped path. This is useful when periodical backups are desired
   *   <li>If outputUrlPrefix is a already a nested path in a GCS bucket, exported data will be put
   *       under this path. This means that a nested path is not reusable, since the export process
   *       by default would not overwrite existing files.
   * </ul>
   *
   * @param outputUrlPrefix the full resource URL of the external storage location
   * @param kinds the datastore 'kinds' to be exported. If empty, all kinds will be exported
   */
  public Export export(String outputUrlPrefix, Collection<String> kinds) {
    return new Export(new ExportRequest(outputUrlPrefix, kinds));
  }

  /**
   * Imports the entire backup specified by {@code backupUrl} back to Cloud Datastore.
   *
   * <p>A successful backup restores deleted entities and reverts updates to existing entities since
   * the backup time. However, it does not affect newly added entities.
   */
  public Import importBackup(String backupUrl) {
    return new Import(new ImportRequest(backupUrl, ImmutableList.of()));
  }

  /**
   * Imports the backup specified by {@code backupUrl} back to Cloud Datastore. Only entities whose
   * types are included in {@code kinds} are imported.
   *
   * @see #importBackup(String)
   */
  public Import importBackup(String backupUrl, Collection<String> kinds) {
    return new Import(new ImportRequest(backupUrl, kinds));
  }

  /**
   * Returns a {@link Get} request that retrieves the details of an export or import {@link
   * Operation}.
   *
   * @param operationName name of the {@code Operation} as returned by an export or import request
   */
  public Get get(String operationName) {
    return new Get(operationName);
  }

  /**
   * Returns a {@link ListOperations} request that retrieves all export or import {@link Operation
   * operations} matching {@code filter}.
   *
   * <p>Sample usage: find all operations started after 2018-10-31 00:00:00 UTC and has stopped:
   *
   * <pre>
   *     {@code String filter = "metadata.common.startTime>\"2018-10-31T0:0:0Z\" AND done=true";}
   *     {@code List<Operation> operations = datastoreAdmin.list(filter);}
   * </pre>
   *
   * <p>Please refer to {@link Operation} for how to reference operation properties.
   */
  public ListOperations list(String filter) {
    checkArgument(!Strings.isNullOrEmpty(filter), "Filter must not be null or empty.");
    return new ListOperations(Optional.of(filter));
  }

  /**
   * Returns a {@link ListOperations} request that retrieves all export or import {@link Operation *
   * operations}.
   */
  public ListOperations listAll() {
    return new ListOperations(Optional.empty());
  }

  /** Builder for {@link DatastoreAdmin}. */
  public static class Builder extends AbstractGoogleJsonClient.Builder {

    private String projectId;

    public Builder(
        HttpTransport httpTransport,
        JsonFactory jsonFactory,
        HttpRequestInitializer httpRequestInitializer) {
      super(httpTransport, jsonFactory, ROOT_URL, SERVICE_PATH, httpRequestInitializer, false);
    }

    @Override
    public Builder setApplicationName(String applicationName) {
      return (Builder) super.setApplicationName(applicationName);
    }

    /** Sets the GCP project ID of the Cloud Datastore databases being managed. */
    public Builder setProjectId(String projectId) {
      this.projectId = projectId;
      return this;
    }

    @Override
    public DatastoreAdmin build() {
      return new DatastoreAdmin(this);
    }
  }

  /** A request to export Cloud Datastore databases. */
  public class Export extends DatastoreAdminRequest<Operation> {

    Export(ExportRequest exportRequest) {
      super(
          DatastoreAdmin.this,
          "POST",
          "projects/{projectId}:export",
          exportRequest,
          Operation.class);
      set("projectId", projectId);
    }
  }

  /** A request to restore an backup to a Cloud Datastore database. */
  public class Import extends DatastoreAdminRequest<Operation> {

    Import(ImportRequest importRequest) {
      super(
          DatastoreAdmin.this,
          "POST",
          "projects/{projectId}:import",
          importRequest,
          Operation.class);
      set("projectId", projectId);
    }
  }

  /** A request to retrieve details of an export or import operation. */
  public class Get extends DatastoreAdminRequest<Operation> {

    Get(String operationName) {
      super(DatastoreAdmin.this, "GET", operationName, null, Operation.class);
    }
  }

  /** A request to retrieve all export or import operations matching a given filter. */
  public class ListOperations extends DatastoreAdminRequest<Operation.OperationList> {

    ListOperations(Optional<String> filter) {
      super(
          DatastoreAdmin.this,
          "GET",
          "projects/{projectId}/operations",
          null,
          Operation.OperationList.class);
      set("projectId", projectId);
      filter.ifPresent(f -> set("filter", f));
    }
  }

  /** Base class of all DatastoreAdmin requests. */
  abstract static class DatastoreAdminRequest<T> extends AbstractGoogleJsonClientRequest<T> {
    /**
     * @param client Google JSON client
     * @param requestMethod HTTP Method
     * @param uriTemplate URI template for the path relative to the base URL. If it starts with a
     *     "/" the base path from the base URL will be stripped out. The URI template can also be a
     *     full URL. URI template expansion is done using {@link
     *     com.google.api.client.http.UriTemplate#expand(String, String, Object, boolean)}
     * @param jsonContent POJO that can be serialized into JSON content or {@code null} for none
     * @param responseClass response class to parse into
     */
    protected DatastoreAdminRequest(
        DatastoreAdmin client,
        String requestMethod,
        String uriTemplate,
        Object jsonContent,
        Class<T> responseClass) {
      super(client, requestMethod, uriTemplate, jsonContent, responseClass);
    }
  }

  /**
   * Model object that describes the JSON content in an export request.
   *
   * <p>Please note that some properties defined in the API are excluded, e.g., {@code databaseId}
   * (not supported by Cloud Datastore) and labels (not used by Domain Registry).
   */
  @SuppressWarnings("unused")
  static class ExportRequest extends GenericJson {
    @Key private final String outputUrlPrefix;
    @Key private final EntityFilter entityFilter;

    ExportRequest(String outputUrlPrefix, Collection<String> kinds) {
      checkNotNull(outputUrlPrefix, "outputUrlPrefix");
      checkArgument(!kinds.isEmpty(), "kinds must not be empty");
      this.outputUrlPrefix = outputUrlPrefix;
      this.entityFilter = new EntityFilter(kinds);
    }
  }

  /**
   * Model object that describes the JSON content in an export request.
   *
   * <p>Please note that some properties defined in the API are excluded, e.g., {@code databaseId}
   * (not supported by Cloud Datastore) and labels (not used by Domain Registry).
   */
  @SuppressWarnings("unused")
  static class ImportRequest extends GenericJson {
    @Key private final String inputUrl;
    @Key private final EntityFilter entityFilter;

    ImportRequest(String inputUrl, Collection<String> kinds) {
      checkNotNull(inputUrl, "outputUrlPrefix");
      this.inputUrl = inputUrl;
      this.entityFilter = new EntityFilter(kinds);
    }
  }
}
