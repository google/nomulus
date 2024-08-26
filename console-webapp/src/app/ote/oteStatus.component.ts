// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RegistrarService } from '../registrar/registrar.service';
import { SharedModule } from '../app.module';
import { MaterialModule } from '../material.module';
import { SnackBarModule } from '../snackbar.module';
import { CommonModule } from '@angular/common';

export interface OteStatusResponse {
  description: string;
  requirement: number;
  timesPerformed: number;
  completed: boolean;
}

@Component({
  selector: 'app-ote-status',
  standalone: true,
  imports: [MaterialModule, SnackBarModule, CommonModule, SharedModule],
  templateUrl: './oteStatus.component.html',
  styleUrls: ['./oteStatus.component.scss'],
})
export class OteStatusComponent {
  oteStatusResponse = signal<OteStatusResponse[]>([]);

  oteStatusCompleted = computed(() =>
    this.oteStatusResponse().filter((v) => v.completed)
  );
  oteStatusUnfinished = computed(() =>
    this.oteStatusResponse().filter((v) => !v.completed)
  );
  isOte = computed(
    () => this.registrarService.registrar()?.type?.toLowerCase() === 'ote'
  );

  constructor(
    protected registrarService: RegistrarService,
    private _snackBar: MatSnackBar
  ) {
    this.registrarService
      .oteStatus(this.registrarService.registrarId())
      .subscribe({
        next: (oteStatusResponse: OteStatusResponse[]) => {
          this.oteStatusResponse.set(oteStatusResponse);
        },
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.error || err.message);
        },
      });
  }
}
