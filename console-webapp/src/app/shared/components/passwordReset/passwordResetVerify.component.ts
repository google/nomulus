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
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { take } from 'rxjs';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { BackendService } from '../../services/backend.service';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import {
  PasswordInputForm,
  PasswordResults,
} from './passwordInputForm.component';
import EppPasswordEditComponent from 'src/app/settings/security/eppPasswordEdit.component';
import { MatSnackBar } from '@angular/material/snack-bar';

export interface PasswordResetVerifyResponse {
  registrarId: string;
  type: string;
}

@Component({
  selector: 'app-password-reset-verify',
  templateUrl: './passwordResetVerify.component.html',
  standalone: false,
})
export class PasswordResetVerifyComponent {
  public static PATH = 'password-reset-verify';

  REGISTRY_LOCK_PASSWORD_VALIDATORS = [
    Validators.required,
    PasswordInputForm.newPasswordsMatch,
  ];

  isLoading = true;
  type?: string;
  errorMessage?: string;
  requestVerificationCode = '';

  passwordUpdateForm: FormGroup<any> | null = null;

  constructor(
    protected backendService: BackendService,
    protected registrarService: RegistrarService,
    private route: ActivatedRoute,
    private router: Router,
    private _snackBar: MatSnackBar
  ) {}

  ngOnInit() {
    this.route.queryParamMap.pipe(take(1)).subscribe((params: ParamMap) => {
      this.requestVerificationCode =
        params.get('resetRequestVerificationCode') || '';
      this.backendService
        .getPasswordResetInformation(this.requestVerificationCode)
        .subscribe({
          error: (err: HttpErrorResponse) => {
            this.isLoading = false;
            this.errorMessage = err.error;
          },
          next: this.presentData.bind(this),
        });
    });
  }

  presentData(verificationResponse: PasswordResetVerifyResponse) {
    this.type = verificationResponse.type === 'EPP' ? 'EPP' : 'Registry lock';
    this.registrarService.registrarId.set(verificationResponse.registrarId);
    const validators =
      verificationResponse.type === 'EPP'
        ? EppPasswordEditComponent.EPP_VALIDATORS
        : this.REGISTRY_LOCK_PASSWORD_VALIDATORS;

    this.passwordUpdateForm = new FormGroup({
      newPassword: new FormControl('', validators),
      newPasswordRepeat: new FormControl('', validators),
    });
    this.isLoading = false;
  }

  save(passwordResults: PasswordResults) {
    this.backendService
      .finalizePasswordReset(
        this.requestVerificationCode,
        passwordResults.newPassword
      )
      .subscribe({
        error: (err: HttpErrorResponse) => {
          this.isLoading = false;
          this.errorMessage = err.error;
        },
        next: (_) => {
          this.router.navigate(['']);
          this._snackBar.open('Password reset completed successfully');
        },
      });
  }
}
