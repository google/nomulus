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

import { Component, EventEmitter, input, Output } from '@angular/core';
import { AbstractControl, FormGroup, ValidatorFn } from '@angular/forms';

type errorCode = 'required' | 'maxlength' | 'minlength' | 'passwordsDontMatch';

type errorFriendlyText = { [type in errorCode]: String };

export interface PasswordResults {
  oldPassword: string | null;
  newPassword: string;
  newPasswordRepeat: string;
}

@Component({
  selector: 'password-input-form-component',
  templateUrl: './passwordInputForm.component.html',
  styleUrls: ['./passwordInputForm.component.scss'],
  standalone: false,
})
export class PasswordInputForm {
  static newPasswordsMatch: ValidatorFn = (control: AbstractControl) => {
    const parent = control.parent;
    if (
      parent?.get('newPassword')?.value ===
      parent?.get('newPasswordRepeat')?.value
    ) {
      parent?.get('newPasswordRepeat')?.setErrors(null);
    } else {
      // latest angular just won't detect the error without setTimeout
      setTimeout(() => {
        parent
          ?.get('newPasswordRepeat')
          ?.setErrors({ passwordsDontMatch: control.value });
      });
    }
    return null;
  };

  MIN_MAX_LENGTH = 'Passwords must be between 6 and 16 alphanumeric characters';

  errorTextMap: errorFriendlyText = {
    required: "This field can't be empty",
    maxlength: this.MIN_MAX_LENGTH,
    minlength: this.MIN_MAX_LENGTH,
    passwordsDontMatch: "Passwords don't match",
  };

  displayOldPasswordField = input<boolean>(false);
  formGroup = input<FormGroup>();
  @Output() submitResults = new EventEmitter<PasswordResults>();

  hasError(controlName: string) {
    const maybeErrors = this.formGroup()!.get(controlName)?.errors;
    const maybeError =
      maybeErrors && (Object.keys(maybeErrors)[0] as errorCode);
    if (maybeError) {
      return this.errorTextMap[maybeError];
    }
    return '';
  }

  save() {
    const results: PasswordResults = this.formGroup()!.value;
    if (this.displayOldPasswordField() && !results.oldPassword) return;
    if (!results.newPassword || !results.newPasswordRepeat) return;
    this.submitResults.emit(results);
  }
}
