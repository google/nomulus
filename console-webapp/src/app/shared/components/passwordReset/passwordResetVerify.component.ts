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
import PasswordUpdateFormComponent from './passwordUpdateFormComponent';

export interface PasswordResetVerifyResponse {
  registrarId: string;
  type: string;
}

@Component({
  selector: 'app-password-reset-verify',
  templateUrl: './passwordResetVerify.component.html',
  styleUrls: ['./passwordResetVerify.component.scss'],
  standalone: false,
})
export class PasswordResetVerifyComponent extends PasswordUpdateFormComponent {
  public static PATH = 'password-reset-verify';

  EPP_PASSWORD_VALIDATORS = [
    Validators.required,
    Validators.minLength(6),
    Validators.maxLength(16),
    this.newPasswordsMatch,
  ];

  REGISTRY_LOCK_PASSWORD_VALIDATORS = [
    Validators.required,
    this.newPasswordsMatch,
  ];

  isLoading = true;
  type?: string;
  errorMessage?: string;
  requestVerificationCode = '';

  constructor(
    protected backendService: BackendService,
    protected registrarService: RegistrarService,
    private route: ActivatedRoute,
    private router: Router
  ) {
    super();
  }

  ngOnInit() {
    this.route.queryParamMap.pipe(take(1)).subscribe((params: ParamMap) => {
      this.requestVerificationCode =
        params.get('requestVerificationCode') || '';
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
        ? this.EPP_PASSWORD_VALIDATORS
        : this.REGISTRY_LOCK_PASSWORD_VALIDATORS;

    this.passwordUpdateForm = new FormGroup({
      newPassword: new FormControl('', validators),
      newPasswordRepeat: new FormControl('', validators),
    });
    this.isLoading = false;
  }

  save() {
    const { newPassword, newPasswordRepeat } = this.passwordUpdateForm.value;
    if (
      !newPassword ||
      !newPasswordRepeat ||
      newPassword !== newPasswordRepeat
    ) {
      return;
    }
    this.backendService
      .finalizePasswordReset(this.requestVerificationCode, newPassword)
      .subscribe({
        error: (err: HttpErrorResponse) => {
          this.isLoading = false;
          this.errorMessage = err.error;
        },
        next: (_) => this.router.navigate(['']),
      });
  }
}
