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

package google.registry.flows;

/**
 * Interface for a {@link Flow} that logs its SQL statements when running transactionally.
 *
 * <p>We don't wish to log all SQL statements ever executed (that'll create too much log bloat) but
 * for some flows and some occasions we may wish to know precisely what SQL statements are being
 * run.
 */
public interface SqlStatementLoggingFlow extends TransactionalFlow {}
