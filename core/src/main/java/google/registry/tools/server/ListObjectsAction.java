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

package google.registry.tools.server;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.model.ImmutableObject;
import google.registry.request.JsonResponse;
import google.registry.request.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.inject.Inject;

/**
 * Abstract base class for actions that list ImmutableObjects.
 *
 * <p>Returns formatted text to be displayed on the screen.
 *
 * @param <T> type of object
 */
public abstract class ListObjectsAction<T extends ImmutableObject> implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String FIELDS_PARAM = "fields";
  public static final String PRINT_HEADER_ROW_PARAM = "printHeaderRow";
  public static final String FULL_FIELD_NAMES_PARAM = "fullFieldNames";

  @Inject JsonResponse response;
  @Inject @Parameter("fields") Optional<String> fields;
  @Inject @Parameter("printHeaderRow") Optional<Boolean> printHeaderRow;
  @Inject @Parameter("fullFieldNames") Optional<Boolean> fullFieldNames;

  /** Returns the set of objects to list, in the desired listing order. */
  abstract ImmutableSet<T> loadObjects();

  /**
   * Returns a set of fields to always include in the output as the leftmost columns.  Subclasses
   * can use this to specify the equivalent of a "primary key" for each object listed.
   */
  ImmutableSet<String> getPrimaryKeyFields() {
    return ImmutableSet.of();
  }

  /**
   * Returns an {@link ImmutableBiMap} that maps any field name aliases to the actual field names.
   *
   * <p>Users can select aliased fields for display using either the original name or the alias.  By
   * default, aliased fields will use the alias name as the header instead of the original name.
   */
  ImmutableBiMap<String, String> getFieldAliases() {
    return ImmutableBiMap.of();
  }

  /**
   * Returns for a given {@link ImmutableObject} a mapping from field names to field values that
   * will override, for any overlapping field names, the default behavior of getting the field
   * value by looking up that field name in the map returned by
   * {@link ImmutableObject#toDiffableFieldMap}.
   *
   * <p>This can be used to specify customized printing of certain fields (e.g. to print out a
   * boolean field as "active" or "-" instead of "true" or "false").  It can also be used to add
   * fields to the data, e.g. for computed fields that can be accessed from the object directly but
   * aren't stored as simple fields.
   */
  ImmutableMap<String, String> getFieldOverrides(@SuppressWarnings("unused") T object) {
    return ImmutableMap.of();
  }

  @Override
  public void run() {
    try {
      // Get the object data first, so we can figure out the list of all available fields using the
      // data if necessary.
      ImmutableSet<T> objects = loadObjects();
      logger.atInfo().log("Loaded %d objects.", objects.size());
      // Get the list of fields we should return.
      ImmutableSet<String> fieldsToUse = getFieldsToUse(objects);
      // Convert the data into a table.
      ImmutableTable<T, String, String> data = extractData(fieldsToUse, objects);
      // Now that we have the data table, compute the column widths.
      ImmutableMap<String, Integer> columnWidths =
          computeColumnWidths(data, isHeaderRowInUse(data));
      // Finally, convert the table to an array of lines of text.
      List<String> lines = generateFormattedData(data, columnWidths);
      // Return the results.
      response.setPayload(ImmutableMap.of(
          "lines", lines,
          "status", "success"));
    } catch (IllegalArgumentException e) {
      logger.atWarning().withCause(e).log("Error while listing objects.");
      // Don't return a non-200 response, since that will cause RegistryTool to barf instead of
      // letting ListObjectsCommand parse the JSON response and return a clean error.
      response.setPayload(
          ImmutableMap.of(
              "error", firstNonNull(e.getMessage(), e.getClass().getName()),
              "status", "error"));
    }
  }

  /**
   * Returns the set of fields to return, aliased or not according to --full_field_names, and
   * with duplicates eliminated but the ordering otherwise preserved.
   */
  private ImmutableSet<String> getFieldsToUse(ImmutableSet<T> objects) {
    // Get the list of fields from the received parameter.
    List<String> fieldsToUse;
    if ((fields == null) || fields.isEmpty()) {
      fieldsToUse = new ArrayList<>();
    } else {
      fieldsToUse = Splitter.on(',').splitToList(fields.get());
      // Check whether any field name is the wildcard; if so, use all fields.
      if (fieldsToUse.contains("*")) {
        fieldsToUse = getAllAvailableFields(objects);
      }
    }
    // Handle aliases according to the state of the fullFieldNames parameter.
    final ImmutableMap<String, String> nameMapping =
        ((fullFieldNames != null) && fullFieldNames.isPresent() && fullFieldNames.get())
            ? getFieldAliases() : getFieldAliases().inverse();
    return Streams.concat(getPrimaryKeyFields().stream(), fieldsToUse.stream())
        .map(field -> nameMapping.getOrDefault(field, field))
        .collect(toImmutableSet());
  }

  /**
   * Constructs a list of all available fields for use by the wildcard field specification.
   * Don't include aliases, since then we'd wind up returning the same field twice.
   */
  private ImmutableList<String> getAllAvailableFields(ImmutableSet<T> objects) {
    ImmutableList.Builder<String> fields = new ImmutableList.Builder<>();
    for (T object : objects) {
      // Base case of the mapping is to use ImmutableObject's toDiffableFieldMap().
      fields.addAll(object.toDiffableFieldMap().keySet());
      // Next, overlay any field-level overrides specified by the subclass.
      fields.addAll(getFieldOverrides(object).keySet());
    }
    return fields.build();
  }

  /**
   * Returns a table of data for the given sets of fields and objects.  The table is row-keyed by
   * object and column-keyed by field, in the same iteration order as the provided sets.
   */
  private ImmutableTable<T, String, String>
      extractData(ImmutableSet<String> fields, ImmutableSet<T> objects) {
    ImmutableTable.Builder<T, String, String> builder = new ImmutableTable.Builder<>();
    for (T object : objects) {
      Map<String, Object> fieldMap = new HashMap<>();
      // Base case of the mapping is to use ImmutableObject's toDiffableFieldMap().
      fieldMap.putAll(object.toDiffableFieldMap());
      // Next, overlay any field-level overrides specified by the subclass.
      fieldMap.putAll(getFieldOverrides(object));
      // Next, add to the mapping all the aliases, with their values defined as whatever was in the
      // map under the aliased field's original name.
      fieldMap.putAll(new HashMap<>(Maps.transformValues(getFieldAliases(), fieldMap::get)));
      Set<String> expectedFields = ImmutableSortedSet.copyOf(fieldMap.keySet());
      for (String field : fields) {
        checkArgument(fieldMap.containsKey(field),
            "Field '%s' not found - recognized fields are:\n%s", field, expectedFields);
        builder.put(object, field, Objects.toString(fieldMap.get(field), ""));
      }
    }
    return builder.build();
  }

  /**
   * Computes the column widths of the given table of strings column-keyed by strings and returns
   * them as a map from column key name to integer width.  The column width is defined as the max
   * length of any string in that column, including the name of the column.
   */
  private static ImmutableMap<String, Integer> computeColumnWidths(
      ImmutableTable<?, String, String> data, final boolean includingHeader) {
    return ImmutableMap.copyOf(
        Maps.transformEntries(
            data.columnMap(),
            (columnName, columnValues) ->
                Streams.concat(
                        Stream.of(includingHeader ? columnName : ""),
                        columnValues.values().stream())
                    .map(String::length)
                    .max(Ordering.natural())
                    .get()));
  }

  /**
   * Check whether to display headers. If the parameter is not set, print headers only if there
   * is more than one column.
   */
  private boolean isHeaderRowInUse(final ImmutableTable<?, String, String> data) {
    return ((printHeaderRow != null) && printHeaderRow.isPresent())
        ? printHeaderRow.get() : (data.columnKeySet().size() > 1);
  }

  /** Converts the provided table of data to text, formatted using the provided column widths. */
  private List<String> generateFormattedData(
      ImmutableTable<T, String, String> data,
      ImmutableMap<String, Integer> columnWidths) {
    Function<Map<String, String>, String> rowFormatter = makeRowFormatter(columnWidths);
    List<String> lines = new ArrayList<>();

    if (isHeaderRowInUse(data)) {
      // Add a row of headers (column names mapping to themselves).
      Map<String, String> headerRow = Maps.asMap(data.columnKeySet(), key -> key);
      lines.add(rowFormatter.apply(headerRow));

      // Add a row of separator lines (column names mapping to '-' * column width).
      Map<String, String> separatorRow = Maps.transformValues(columnWidths, "-"::repeat);
      lines.add(rowFormatter.apply(separatorRow));
    }

    // Add the actual data rows.
    for (Map<String, String> row : data.rowMap().values()) {
      lines.add(rowFormatter.apply(row));
    }

    return lines;
  }

  /**
   * Returns for the given column widths map a row formatting function that converts a row map (of
   * column keys to cell values) into a single string with each column right-padded to that width.
   *
   * <p>The resulting strings separate padded fields with two spaces and each end in a newline.
   */
  private static Function<Map<String, String>, String> makeRowFormatter(
      final Map<String, Integer> columnWidths) {
    return rowByColumns -> {
      List<String> paddedFields = new ArrayList<>();
      for (Map.Entry<String, String> cell : rowByColumns.entrySet()) {
        paddedFields.add(Strings.padEnd(cell.getValue(), columnWidths.get(cell.getKey()), ' '));
      }
      return Joiner.on("  ").join(paddedFields);
    };
  }
}
