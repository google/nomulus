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

import { Component } from '@angular/core';
import { MatSnackBar, MatSnackBarRef } from '@angular/material/snack-bar';
import { RegistrarService } from '../../../registrar/registrar.service';
import { HttpErrorResponse } from '@angular/common/http';

@Component({
  selector: 'app-poc-reminder',
  templateUrl: './pocReminder.component.html',
  styleUrls: ['./pocReminder.component.scss'],
  standalone: false,
})
export class PocReminderComponent {
  constructor(
    public snackBarRef: MatSnackBarRef<PocReminderComponent>,
    private registrarService: RegistrarService,
    private _snackBar: MatSnackBar
  ) {}

  confirmReviewed() {
    if (this.registrarService.registrar()) {
      const todayMidnight = new Date();
      todayMidnight.setHours(0, 0, 0, 0);
      this.registrarService
        // @ts-ignore - if check  above won't allow empty object to be submitted
        .updateRegistrar({
          ...this.registrarService.registrar(),
          lastPocVerificationDate: todayMidnight.toISOString(),
        })
        .subscribe({
          error: (err: HttpErrorResponse) => {
            this._snackBar.open(err.error || err.message);
          },
          next: () => {
            this.snackBarRef.dismiss();
          },
        });
    }
  }
}
