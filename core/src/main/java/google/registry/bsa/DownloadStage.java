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

package google.registry.bsa;

import google.registry.bsa.api.BlockLabel;
import google.registry.bsa.api.BlockOrder;

/** The processing stages of a download. */
public enum DownloadStage {
  /** Downloads BSA block list files. */
  DOWNLOAD_BLOCK_LISTS,
  /**
   * Generates block list diffs against the previous download. The diffs consist of a stream of
   * {@link BlockOrder orders} and a stream of {@link BlockLabel labels}.
   */
  MAKE_ORDER_AND_LABEL_DIFF,
  /** Applies the diffs to the database. */
  APPLY_ORDER_AND_LABEL_DIFF,
  /**
   * Makes a REST API call to BSA endpoint, declaring that processing starts for new orders in the
   * diffs.
   */
  REPORT_START_OF_ORDER_PROCESSING,
  /**
   * Makes a REST API call to BSA endpoint, uploading unblockable domains that match labels in the
   * diff.
   */
  UPLOAD_UNBLOCKABLE_DOMAINS_FOR_NEW_ORDERS,
  /** Makes a REST API call to BSA endpoint, declaring the completion of order processing. */
  REPORT_END_OF_ORDER_PROCESSING,
  /** The terminal stage after processing succeeds. */
  DONE,
  /**
   * The terminal stage indicating that the downloads are discarded because their checksums are the
   * same as that of the previous download.
   */
  NOP,
  /**
   * The terminal stage indicating that the downloads are not processed because their BSA-generated
   * checksums do not match those calculated by us.
   */
  CHECKSUMS_DO_NOT_MATCH;
}
