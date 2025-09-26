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

import { Injectable, signal } from '@angular/core';
import { BackendService } from '../shared/services/backend.service';
import { tap } from 'rxjs';

export interface HistoryRecord {
  modificationTime: string;
  type: string;
  description: string;
  actingUser: {
    emailAddress: string;
  };
}

@Injectable()
export class HistoryService {
  historyRecordsRegistrar = signal<HistoryRecord[]>([]);
  historyRecordsUser = signal<HistoryRecord[]>([]);

  constructor(private backendService: BackendService) {}

  getHistoryLog(registrarId: string, userEmail?: string) {
    return this.backendService.getHistoryLog(registrarId, userEmail).pipe(
      tap((historyRecords: HistoryRecord[]) => {
        if (userEmail) {
          this.historyRecordsUser.set(historyRecords);
        } else {
          this.historyRecordsRegistrar.set(historyRecords);
        }
      })
    );
  }
}
