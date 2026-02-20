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
import { Component, computed, OnInit, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RegistrarService } from '../registrar/registrar.service';
import { MaterialModule } from '../material.module';
import { SnackBarModule } from '../snackbar.module';

import { ActivatedRoute, ParamMap } from '@angular/router';
import { take } from 'rxjs';

export interface OteStatusResponse {
  description: string;
  requirement: number;
  timesPerformed: number;
  completed: boolean;
}

@Component({
  selector: 'app-ote-status',
  imports: [MaterialModule, SnackBarModule],
  templateUrl: './oteStatus.component.html',
  styleUrls: ['./oteStatus.component.scss'],
})
export class OteStatusComponent implements OnInit {
  registrarId = signal<string | null>(null);
  oteStatusResponse = signal<OteStatusResponse[]>([]);

  oteStatusCompleted = computed(() =>
    this.oteStatusResponse().filter((v) => v.completed)
  );
  oteStatusUnfinished = computed(() =>
    this.oteStatusResponse().filter((v) => !v.completed)
  );
  isOte = computed(
    () =>
      this.registrarService
        .registrars()
        .find((r) => r.registrarId === this.registrarId())
        ?.type?.toLowerCase() === 'ote'
  );

  constructor(
    private route: ActivatedRoute,
    protected registrarService: RegistrarService,
    private _snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.route.paramMap.pipe(take(1)).subscribe((params: ParamMap) => {
      this.registrarId.set(params.get('registrarId'));
      const registrarId = this.registrarId();
      if (!registrarId) throw 'Missing registrarId param';

      this.registrarService.oteStatus(registrarId).subscribe({
        next: (oteStatusResponse: OteStatusResponse[]) => {
          this.oteStatusResponse.set(oteStatusResponse);
        },
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.error || err.message);
        },
      });
    });
  }
}
