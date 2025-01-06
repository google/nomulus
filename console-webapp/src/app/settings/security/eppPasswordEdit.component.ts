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
import { Component } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { SecurityService } from './security.service';

type errorCode = 'required' | 'maxlength' | 'minlength' | 'passwordsDontMatch';

type errorFriendlyText = { [type in errorCode]: String };

@Component({
  selector: 'app-epp-password-edit',
  templateUrl: './eppPasswordEdit.component.html',
  styleUrls: ['./eppPasswordEdit.component.scss'],
})
export default class EppPasswordEditComponent {
  MIN_MAX_LENGHT = new String(
    'Passwords must be between 6 and 16 alphanumeric characters'
  );

  errorTextMap: errorFriendlyText = {
    required: "This field can't be empty",
    maxlength: this.MIN_MAX_LENGHT,
    minlength: this.MIN_MAX_LENGHT,
    passwordsDontMatch: "Passwords don't match",
  };

  constructor(
    public securityService: SecurityService,
    private _snackBar: MatSnackBar,
    public registrarService: RegistrarService
  ) {}

  hasError(controlName: string) {
    const maybeErrors = this.passwordUpdateForm.get(controlName)?.errors;
    const maybeError =
      maybeErrors && (Object.keys(maybeErrors)[0] as errorCode);
    if (maybeError) {
      return this.errorTextMap[maybeError];
    }
    return '';
  }

  newPasswordsMatch: ValidatorFn = (control: AbstractControl) => {
    if (
      this.passwordUpdateForm?.get('newPassword')?.value ===
      this.passwordUpdateForm?.get('newPasswordRepeat')?.value
    ) {
      this.passwordUpdateForm?.get('newPasswordRepeat')?.setErrors(null);
    } else {
      // latest angular just won't detect the error without setTimeout
      setTimeout(() => {
        this.passwordUpdateForm
          ?.get('newPasswordRepeat')
          ?.setErrors({ passwordsDontMatch: control.value });
      });
    }
    return null;
  };

  passwordUpdateForm = new FormGroup({
    oldPassword: new FormControl('', [Validators.required]),
    newPassword: new FormControl('', [
      Validators.required,
      Validators.minLength(6),
      Validators.maxLength(16),
      this.newPasswordsMatch,
    ]),
    newPasswordRepeat: new FormControl('', [
      Validators.required,
      Validators.minLength(6),
      Validators.maxLength(16),
      this.newPasswordsMatch,
    ]),
  });

  save() {
    const { oldPassword, newPassword, newPasswordRepeat } =
      this.passwordUpdateForm.value;
    if (!oldPassword || !newPassword || !newPasswordRepeat) return;
    this.securityService
      .saveEppPassword({
        registrarId: this.registrarService.registrarId(),
        oldPassword,
        newPassword,
        newPasswordRepeat,
      })
      .subscribe({
        complete: () => {
          this.goBack();
        },
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.error);
        },
      });
  }

  goBack() {
    this.securityService.isEditingPassword = false;
  }
}
