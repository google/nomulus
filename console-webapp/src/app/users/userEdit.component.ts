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

import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { SelectedRegistrarModule } from '../app.module';
import { MaterialModule } from '../material.module';
import { RegistrarService } from '../registrar/registrar.service';
import { SnackBarModule } from '../snackbar.module';
import { User, UsersService, roleToDescription } from './users.service';

@Component({
  selector: 'app-user-edit',
  templateUrl: './userEdit.component.html',
  styleUrls: ['./userEdit.component.scss'],
  standalone: true,
  imports: [
    MaterialModule,
    SnackBarModule,
    CommonModule,
    SelectedRegistrarModule,
  ],
  providers: [],
})
export class UserEditComponent {
  inEdit = false;
  isPasswordVisible = false;
  isNewUser = false;
  isLoading = false;
  userDetails: User;

  constructor(
    protected registrarService: RegistrarService,
    protected usersService: UsersService,
    private _snackBar: MatSnackBar
  ) {
    this.userDetails = this.usersService
      .users()
      .filter(
        (u) => u.emailAddress === this.usersService.currentlyOpenUserEmail()
      )[0];
    if (this.usersService.isNewUser) {
      this.isNewUser = true;
      this.usersService.isNewUser = false;
    }
  }

  roleToDescription(role: string) {
    return roleToDescription(role);
  }

  deleteUser() {
    this.isLoading = true;
    this.usersService.deleteUser(this.userDetails.emailAddress).subscribe({
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

  goBack() {
    this.usersService.currentlyOpenUserEmail.set('');
  }
}
