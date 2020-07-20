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

import static com.google.common.collect.Lists.newArrayList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateValuesResponse;
import com.google.api.services.sheets.v4.model.ClearValuesRequest;
import com.google.api.services.sheets.v4.model.ClearValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SheetSynchronizer}. */
@RunWith(JUnit4.class)
public class SheetSynchronizerTest {
  private final SheetSynchronizer sheetSynchronizer = new SheetSynchronizer();
  private final Sheets sheetsService = mock(Sheets.class);
  private final Sheets.Spreadsheets spreadsheets = mock(Sheets.Spreadsheets.class);
  private final Sheets.Spreadsheets.Values values = mock(Sheets.Spreadsheets.Values.class);
  private final Sheets.Spreadsheets.Values.Get getReq = mock(Sheets.Spreadsheets.Values.Get.class);
  private final Sheets.Spreadsheets.Values.Append appendReq =
      mock(Sheets.Spreadsheets.Values.Append.class);
  private final Sheets.Spreadsheets.Values.BatchUpdate updateReq =
      mock(Sheets.Spreadsheets.Values.BatchUpdate.class);
  private final Sheets.Spreadsheets.Values.Clear clearReq =
      mock(Sheets.Spreadsheets.Values.Clear.class);

  private List<List<Object>> existingSheet;
  private ImmutableList<ImmutableMap<String, String>> data;

  @Before
  public void before() throws Exception {
    sheetSynchronizer.sheetsService = sheetsService;
    when(sheetsService.spreadsheets()).thenReturn(spreadsheets);
    when(spreadsheets.values()).thenReturn(values);

    when(values.get(any(String.class), any(String.class))).thenReturn(getReq);
    when(values.append(any(String.class), any(String.class), any(ValueRange.class)))
        .thenReturn(appendReq);
    when(values.clear(any(String.class), any(String.class), any(ClearValuesRequest.class)))
        .thenReturn(clearReq);
    when(values.batchUpdate(any(String.class), any(BatchUpdateValuesRequest.class)))
        .thenReturn(updateReq);

    when(appendReq.execute()).thenReturn(new AppendValuesResponse());
    when(appendReq.setValueInputOption(any(String.class))).thenReturn(appendReq);
    when(appendReq.setInsertDataOption(any(String.class))).thenReturn(appendReq);
    when(clearReq.execute()).thenReturn(new ClearValuesResponse());
    when(updateReq.execute()).thenReturn(new BatchUpdateValuesResponse());

    existingSheet = newArrayList();
    data = ImmutableList.of();
    ValueRange valueRange = new ValueRange().setValues(existingSheet);
    when(getReq.execute()).thenReturn(valueRange);
  }

  // Explicitly constructs a List<Object> to avoid newArrayList typing to ArrayList<String>
  private List<Object> createRow(Object... elements) {
    return new ArrayList<>(Arrays.asList(elements));
  }

  @Test
  public void testSynchronize_dataAndSheetEmpty_doNothing() throws Exception {
    existingSheet.add(createRow("a", "b"));
    sheetSynchronizer.synchronize("aSheetId", data);
    verifyNoInteractions(appendReq);
    verifyNoInteractions(clearReq);
    verifyNoInteractions(updateReq);
  }

  @Test
  public void testSynchronize_differentValues_updatesValues() throws Exception {
    existingSheet.add(createRow("a", "b"));
    existingSheet.add(createRow("diffVal1l", "diffVal2"));
    data = ImmutableList.of(ImmutableMap.of("a", "val1", "b", "val2"));
    sheetSynchronizer.synchronize("aSheetId", data);

    verifyNoInteractions(appendReq);
    verifyNoInteractions(clearReq);

    BatchUpdateValuesRequest expectedRequest = new BatchUpdateValuesRequest();
    List<List<Object>> expectedVals = newArrayList();
    expectedVals.add(createRow("val1", "val2"));
    expectedRequest.setData(
        newArrayList(new ValueRange().setRange("Registrars!A2").setValues(expectedVals)));
    expectedRequest.setValueInputOption("RAW");
    verify(values).batchUpdate("aSheetId", expectedRequest);
  }

  @Test
  public void testSynchronize_unknownFields_doesntUpdate() throws Exception {
    existingSheet.add(createRow("a", "c", "b"));
    existingSheet.add(createRow("diffVal1", "sameVal", "diffVal2"));
    data = ImmutableList.of(ImmutableMap.of("a", "val1", "b", "val2", "d", "val3"));
    sheetSynchronizer.synchronize("aSheetId", data);

    verifyNoInteractions(appendReq);
    verifyNoInteractions(clearReq);

    BatchUpdateValuesRequest expectedRequest = new BatchUpdateValuesRequest();
    List<List<Object>> expectedVals = newArrayList();
    expectedVals.add(createRow("val1", "sameVal", "val2"));
    expectedRequest.setData(
        newArrayList(new ValueRange().setRange("Registrars!A2").setValues(expectedVals)));
    expectedRequest.setValueInputOption("RAW");
    verify(values).batchUpdate("aSheetId", expectedRequest);
  }

  @Test
  public void testSynchronize_notFullRow_getsPadded() throws Exception {
    existingSheet.add(createRow("a", "c", "b"));
    existingSheet.add(createRow("diffVal1", "diffVal2"));
    data = ImmutableList.of(ImmutableMap.of("a", "val1", "b", "paddedVal", "d", "val3"));
    sheetSynchronizer.synchronize("aSheetId", data);

    verifyNoInteractions(appendReq);
    verifyNoInteractions(clearReq);

    BatchUpdateValuesRequest expectedRequest = new BatchUpdateValuesRequest();
    List<List<Object>> expectedVals = newArrayList();
    expectedVals.add(createRow("val1", "diffVal2", "paddedVal"));
    expectedRequest.setData(
        newArrayList(new ValueRange().setRange("Registrars!A2").setValues(expectedVals)));
    expectedRequest.setValueInputOption("RAW");
    verify(values).batchUpdate("aSheetId", expectedRequest);
  }

  @Test
  public void testSynchronize_moreData_appendsValues() throws Exception {
    existingSheet.add(createRow("a", "b"));
    existingSheet.add(createRow("diffVal1", "diffVal2"));
    data = ImmutableList.of(
        ImmutableMap.of("a", "val1", "b", "val2"),
        ImmutableMap.of("a", "val3", "b", "val4"));
    sheetSynchronizer.synchronize("aSheetId", data);

    verifyNoInteractions(clearReq);

    BatchUpdateValuesRequest expectedRequest = new BatchUpdateValuesRequest();
    List<List<Object>> updatedVals = newArrayList();
    updatedVals.add(createRow("val1", "val2"));
    expectedRequest.setData(
        newArrayList(
            new ValueRange().setRange("Registrars!A2").setValues(updatedVals)));
    expectedRequest.setValueInputOption("RAW");
    verify(values).batchUpdate("aSheetId", expectedRequest);

    List<List<Object>> appendedVals = newArrayList();
    appendedVals.add(createRow("val3", "val4"));
    ValueRange appendRequest = new ValueRange().setValues(appendedVals);
    verify(values).append("aSheetId", "Registrars!A3", appendRequest);
  }

  @Test
  public void testSynchronize_lessData_clearsValues() throws Exception {
    existingSheet.add(createRow("a", "b"));
    existingSheet.add(createRow("val1", "val2"));
    existingSheet.add(createRow("diffVal3", "diffVal4"));
    data = ImmutableList.of(ImmutableMap.of("a", "val1", "b", "val2"));
    sheetSynchronizer.synchronize("aSheetId", data);

    verify(values).clear("aSheetId", "Registrars!3:4", new ClearValuesRequest());
    verifyNoInteractions(updateReq);
  }
}
