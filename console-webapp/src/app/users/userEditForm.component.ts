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

import { CommonModule } from '@angular/common';
import {
  Component,
  ElementRef,
  EventEmitter,
  input,
  Output,
  ViewChild,
} from '@angular/core';
import { MaterialModule } from '../material.module';
import { FormsModule } from '@angular/forms';
import { User } from './users.service';

@Component({
  selector: 'app-user-edit-form',
  templateUrl: './userEditForm.component.html',
  styleUrls: ['./userEditForm.component.scss'],
  standalone: true,
  imports: [FormsModule, MaterialModule, CommonModule],
  providers: [],
})
export class UserEditFormComponent {
  @ViewChild('form') form!: ElementRef;
  isNew = input<boolean>(false);
  user = input<User>(
    {
      emailAddress: '',
      role: 'ACCOUNT_MANAGER',
    },
    // @ts-ignore - legit option, typescript fails to match it to a proper type
    { transform: (user: User) => structuredClone(user) }
  );

  @Output() onEditComplete = new EventEmitter<User>();

  saveEdit(e: SubmitEvent) {
    e.preventDefault();
    if (this.form.nativeElement.checkValidity()) {
      this.onEditComplete.emit(this.user());
    } else {
      this.form.nativeElement.reportValidity();
    }
  }
}
