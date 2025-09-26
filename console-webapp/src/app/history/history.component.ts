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

import { Component, effect } from '@angular/core';
import { UserDataService } from '../shared/services/userData.service';
import { BackendService } from '../shared/services/backend.service';
import { RegistrarService } from '../registrar/registrar.service';
import { HistoryService } from './history.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  GlobalLoader,
  GlobalLoaderService,
} from '../shared/services/globalLoader.service';
import { HttpErrorResponse } from '@angular/common/http';
import { RESTRICTED_ELEMENTS } from '../shared/directives/userLevelVisiblity.directive';

@Component({
  selector: 'app-history',
  templateUrl: './history.component.html',
  styleUrls: ['./history.component.scss'],
  providers: [HistoryService],
  standalone: false,
})
export class HistoryComponent implements GlobalLoader {
  public static PATH = 'history';

  consoleUserEmail: string = '';
  isLoading: boolean = false;

  constructor(
    private backendService: BackendService,
    private registrarService: RegistrarService,
    protected historyService: HistoryService,
    protected globalLoader: GlobalLoaderService,
    protected userDataService: UserDataService,
    private _snackBar: MatSnackBar
  ) {
    effect(() => {
      if (registrarService.registrarId()) {
        this.loadHistory();
      }
    });
  }

  getElementIdForUserLog() {
    return RESTRICTED_ELEMENTS.ACTIVITY_PER_USER;
  }

  loadingTimeout() {
    this._snackBar.open('Timeout loading records history');
  }

  loadHistory() {
    this.globalLoader.startGlobalLoader(this);
    this.isLoading = true;
    this.historyService
      .getHistoryLog(this.registrarService.registrarId(), this.consoleUserEmail)
      .subscribe({
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.error || err.message);
          this.isLoading = false;
        },
        next: () => {
          this.globalLoader.stopGlobalLoader(this);
          this.isLoading = false;
        },
      });
  }
}
