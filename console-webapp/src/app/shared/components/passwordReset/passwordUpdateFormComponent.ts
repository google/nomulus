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

import { AbstractControl, FormGroup, ValidatorFn } from '@angular/forms';

type errorCode = 'required' | 'maxlength' | 'minlength' | 'passwordsDontMatch';

type errorFriendlyText = { [type in errorCode]: String };

export default class PasswordUpdateFormComponent {
  MIN_MAX_LENGTH = new String(
    'Passwords must be between 6 and 16 alphanumeric characters'
  );

  errorTextMap: errorFriendlyText = {
    required: "This field can't be empty",
    maxlength: this.MIN_MAX_LENGTH,
    minlength: this.MIN_MAX_LENGTH,
    passwordsDontMatch: "Passwords don't match",
  };

  passwordUpdateForm: FormGroup<any> = new FormGroup('');

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
}
