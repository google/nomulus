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

import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { HistoryRecord } from './history.service';

@Component({
  selector: 'app-history-list',
  templateUrl: './historyList.component.html',
  styleUrls: ['./historyList.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: false,
})
export class HistoryListComponent {
  @Input() historyRecords: HistoryRecord[] = [];
  @Input() isLoading: boolean = false;

  getIconForType(type: string): string {
    switch (type) {
      case 'REGISTRAR_UPDATE':
        return 'edit';
      case 'REGISTRAR_SECURITY_UPDATE':
        return 'security';
      default:
        return 'history'; // A fallback icon
    }
  }

  getIconClass(type: string): string {
    switch (type) {
      case 'REGISTRAR_UPDATE':
        return 'history-log__icon--update';
      case 'REGISTRAR_SECURITY_UPDATE':
        return 'history-log__icon--security';
      default:
        return '';
    }
  }

  parseDescription(description: string): {
    main: string;
    detail: string | null;
  } {
    if (!description) {
      return { main: 'N/A', detail: null };
    }
    const parts = description.split('|');
    const detail = parts.length > 1 ? parts[1].replace(/_/g, ' ') : parts[0];

    return {
      main: parts[0],
      detail: detail,
    };
  }
}
