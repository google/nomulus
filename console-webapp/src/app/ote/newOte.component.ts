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

import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, signal } from '@angular/core';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RegistrarService } from '../registrar/registrar.service';
import { MaterialModule } from '../material.module';
import { SnackBarModule } from '../snackbar.module';

export interface OteCreateResponse extends Map<string, string> {
  password: string;
}

@Component({
  selector: 'app-ote',
  standalone: true,
  imports: [MaterialModule, SnackBarModule],
  templateUrl: './newOte.component.html',
  styleUrls: ['./newOte.component.scss'],
})
export class NewOteComponent {
  oteCreateResponse = signal<OteCreateResponse | undefined>(undefined);

  readonly oteCreateResponseFormatted = computed(() => {
    const oteCreateResponse = this.oteCreateResponse();
    if (oteCreateResponse) {
      const { password } = oteCreateResponse;
      return Object.entries(oteCreateResponse)
        .filter((entry) => entry[0] !== 'password')
        .map(
          ([login, tld]) =>
            `Login: ${login}\t\tPassword: ${password}\t\tTLD: ${tld}`
        )
        .join('\n');
    }
    return undefined;
  });

  createOte = new FormGroup({
    registrarId: new FormControl('', [Validators.required]),
    registrarEmail: new FormControl('', [Validators.required]),
  });

  constructor(
    protected registrarService: RegistrarService,
    private _snackBar: MatSnackBar
  ) {}

  onSubmit() {
    if (this.createOte.valid) {
      const { registrarId, registrarEmail } = this.createOte.value;
      this.registrarService
        .generateOte(
          {
            registrarId,
            registrarEmail,
          },
          registrarId || ''
        )
        .subscribe({
          next: (oteCreateResponse: OteCreateResponse) => {
            this.oteCreateResponse.set(oteCreateResponse);
          },
          error: (err: HttpErrorResponse) => {
            this._snackBar.open(err.error || err.message);
          },
        });
    }
  }
}
