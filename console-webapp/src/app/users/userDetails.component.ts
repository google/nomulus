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

import { Component, computed } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { SelectedRegistrarModule } from '../app.module';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { SnackBarModule } from '../snackbar.module';
import { UsersService, roleToDescription, User } from './users.service';
import { FormsModule } from '@angular/forms';
import { UserEditFormComponent } from './userEditForm.component';

@Component({
  selector: 'app-user-edit',
  templateUrl: './userDetails.component.html',
  styleUrls: ['./userDetails.component.scss'],
  imports: [
    FormsModule,
    MaterialModule,
    SnackBarModule,
    SelectedRegistrarModule,
    UserEditFormComponent,
  ],
  providers: [],
})
export class UserDetailsComponent {
  isEditing = false;
  isPasswordVisible = false;
  isNewUser = false;
  isLoading = false;

  userDetails = computed(() => {
    return this.usersService
      .users()
      .filter(
        (u) => u.emailAddress === this.usersService.currentlyOpenUserEmail()
      )[0];
  });

  constructor(
    protected registrarService: RegistrarService,
    protected usersService: UsersService,
    private _snackBar: MatSnackBar
  ) {
    if (this.usersService.isNewUser) {
      this.isNewUser = true;
      this.usersService.isNewUser = false;
    }
  }

  roleToDescription(role: string) {
    return roleToDescription(role);
  }

  deleteUser() {
    if (
      confirm(
        'This will permanently delete the user ' +
          this.userDetails().emailAddress
      )
    ) {
      this.isLoading = true;
      this.usersService.deleteUser(this.userDetails()).subscribe({
        error: (err) => {
          this._snackBar.open(err.error || err.message);
          this.isLoading = false;
        },
        complete: () => {
          this.isLoading = false;
          this.goBack();
        },
      });
    }
  }

  goBack() {
    this.usersService.currentlyOpenUserEmail.set('');
  }

  saveEdit(user: User) {
    this.isLoading = true;
    this.usersService.updateUser(user).subscribe({
      error: (err) => {
        this._snackBar.open(err.error || err.message);
        this.isLoading = false;
      },
      complete: () => {
        this.isLoading = false;
        this.isEditing = false;
      },
    });
  }
}
