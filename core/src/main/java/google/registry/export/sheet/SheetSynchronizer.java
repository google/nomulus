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

package google.registry.export.sheet;

import static com.google.common.base.Strings.nullToEmpty;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesResponse;
import com.google.api.services.sheets.v4.model.ClearValuesRequest;
import com.google.api.services.sheets.v4.model.ClearValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/** Generic data synchronization utility for Google Spreadsheets. */
class SheetSynchronizer {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String SHEET_NAME = "Registrars";

  @Inject Sheets sheetsService;
  @Inject SheetSynchronizer() {}

  /**
   * Replace the contents of a Google Spreadsheet with {@code data}.
   *
   * <p>In order for this to work, you must create a spreadsheet with a header row, each containing
   * the column name, without any spaces. All subsequent rows are considered data, so long as
   * they're not blank. If you have a blank row in the middle of your data, you're going to have
   * problems. You must also make sure that the spreadsheet has been shared with the API client
   * credential email address.
   *
   * <p>The algorithm works by first assuming that the spreadsheet is sorted in the same way that
   * {@code data} is sorted (i.e. registrar name order). It then iterates through the existing rows
   * and comparing them to the items in {@code data}. Iteration continues until we either run out of
   * rows, or items in {@code data}. If there's any rows remaining, they'll be deleted. If instead,
   * items remain in data, they'll be appended to the end of the sheet.
   *
   * @param spreadsheetId The ID of your spreadsheet. This can be obtained by opening the Google
   *     spreadsheet in your browser and copying the ID from the URL.
   * @param data This should be a <i>sorted</i> list of rows containing the entirety of the
   *     spreadsheet. Each row is a map, where the key must be exactly the same as the column header
   *     cell in the spreadsheet, and value is an arbitrary object which will be converted to a
   *     string before storing it in the spreadsheet.
   * @throws IOException if encountering an error communicating with the Sheets service.
   * @see <a href="https://developers.google.com/sheets/">Google Sheets API v4</a>
   */
  void synchronize(String spreadsheetId, ImmutableList<ImmutableMap<String, String>> data)
      throws IOException {

    // Get the existing sheet's values
    ValueRange sheetValues =
        sheetsService.spreadsheets().values().get(spreadsheetId, SHEET_NAME).execute();
    List<List<Object>> originalVals = sheetValues.getValues();

    // Assemble headers from the sheet
    ImmutableList.Builder<String> headersBuilder = new ImmutableList.Builder<>();
    for (Object headerCell : originalVals.get(0)) {
      headersBuilder.add(headerCell.toString());
    }
    ImmutableList<String> headers = headersBuilder.build();
    // Pop off the headers row
    originalVals.remove(0);

    List<ValueRange> updates = new ArrayList<>();
    int minSize = Math.min(originalVals.size(), data.size());
    for (int i = 0; i < minSize; i++) {
      boolean mutated = false;
      List<Object> cellRow = originalVals.get(i);
      // If the row isn't full, pad it with empty strings until it is
      while (cellRow.size() < headers.size()) {
        cellRow.add("");
      }
      for (int j = 0; j < headers.size(); j++) {
        // Look for the value corresponding to the row and header indices in data
        String dataField = data.get(i).get(headers.get(j));
        // If the cell's header matches a data header, and the values aren't equal, mutate it
        if (dataField != null && !cellRow.get(j).toString().equals(dataField)) {
          mutated = true;
          originalVals.get(i).set(j, dataField);
        }
      }
      if (mutated) {
        ValueRange rowUpdate =
            new ValueRange()
                .setValues(originalVals.subList(i, i + 1))
                .setRange(getCellRange(i));
        updates.add(rowUpdate);
      }
    }
    // Update the mutated cells if necessary
    if (!updates.isEmpty()) {
      BatchUpdateValuesRequest updateRequest = new BatchUpdateValuesRequest()
          .setValueInputOption("RAW")
          .setData(updates);

      BatchUpdateValuesResponse response =
          sheetsService.spreadsheets().values().batchUpdate(spreadsheetId, updateRequest).execute();
      Integer cellsUpdated = response.getTotalUpdatedCells();
      logger.atInfo().log("Updated %d originalVals.", cellsUpdated != null ? cellsUpdated : 0);
    }

    // Append extra rows if necessary
    if (data.size() > originalVals.size()) {
      ImmutableList.Builder<List<Object>> valsBuilder = new ImmutableList.Builder<>();
      for (int i = originalVals.size(); i < data.size(); i++) {
        ImmutableList.Builder<Object> rowBuilder = new ImmutableList.Builder<>();
        for (String header : headers) {
          rowBuilder.add(nullToEmpty(data.get(i).get(header)));
        }
        valsBuilder.add(rowBuilder.build());
      }
      // Start the append at index originalVals.size (where the previous operation left off)
      ValueRange appendUpdate = new ValueRange().setValues(valsBuilder.build());
      AppendValuesResponse appendResponse = sheetsService
          .spreadsheets()
          .values()
          .append(spreadsheetId, getCellRange(originalVals.size()), appendUpdate)
          .setValueInputOption("RAW")
          .setInsertDataOption("INSERT_ROWS")
          .execute();
      logger.atInfo().log(
          "Appended %d rows to range %s.",
          data.size() - originalVals.size(), appendResponse.getTableRange());
    // Clear the extra rows if necessary
    } else if (data.size() < originalVals.size()) {
      // Clear other rows if there's more originalVals on the sheet than live data.
      ClearValuesResponse clearResponse =
          sheetsService
              .spreadsheets()
              .values()
              .clear(
                  spreadsheetId,
                  getRowRange(data.size(), originalVals.size()),
                  new ClearValuesRequest())
              .execute();
      logger.atInfo().log(
          "Cleared %d rows from range %s.",
          originalVals.size() - data.size(), clearResponse.getClearedRange());
    }
  }

  /**
   * Returns an A1 representation of the cell indicating the top-left corner of the update.
   *
   * <p>Updates and appends can specify either a complete range (i.e. A1:B4), or just the top-left
   * corner of the request. For the latter case, the data fills in based on the primary dimension
   * (either row-major or column-major order, default is row-major.) For simplicity, we just specify
   * a single cell for these requests.
   *
   * @see <a href="https://developers.google.com/sheets/api/guides/values#writing_to_a_single_range">
   *   Writing to a single range</a>
   */
  private String getCellRange(int rowNum) {
    // We add 1 to rowNum to compensate for Sheet's 1-indexing, and 1 to offset for the header
    return String.format("%s!A%d", SHEET_NAME, rowNum + 2);
  }

  /**
   * Returns an A1 representation of the cell indicating an entire set of rows.
   *
   * <p>Clear requests require a specific range, and we always want to clear an entire row at a time
   * (to avoid leaving any data behind).
   */
  private String getRowRange(int firstRow, int lastRow) {
    return String.format("%s!%d:%d", SHEET_NAME, firstRow + 2, lastRow + 2);
  }
}
