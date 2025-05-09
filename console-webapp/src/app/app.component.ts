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

import { AfterViewInit, Component, effect, ViewChild } from '@angular/core';
import { MatSidenav } from '@angular/material/sidenav';
import { NavigationEnd, Router } from '@angular/router';
import { RegistrarService } from './registrar/registrar.service';
import { BreakPointObserverService } from './shared/services/breakPoint.service';
import { GlobalLoaderService } from './shared/services/globalLoader.service';
import { UserDataService } from './shared/services/userData.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { PocReminderComponent } from './shared/components/pocReminder/pocReminder.component';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss'],
  standalone: false,
})
export class AppComponent implements AfterViewInit {
  @ViewChild(MatSidenav)
  sidenav!: MatSidenav;

  constructor(
    protected registrarService: RegistrarService,
    protected userDataService: UserDataService,
    protected globalLoader: GlobalLoaderService,
    protected breakpointObserver: BreakPointObserverService,
    private router: Router,
    private _snackBar: MatSnackBar
  ) {
    effect(() => {
      const registrar = this.registrarService.registrar();
      const oneYearAgo = new Date();
      oneYearAgo.setFullYear(oneYearAgo.getFullYear() - 1);
      if (
        registrar &&
        registrar.lastPocVerificationDate &&
        new Date(registrar.lastPocVerificationDate) < oneYearAgo &&
        this.userDataService?.userData()?.globalRole === 'NONE'
      ) {
        this._snackBar.openFromComponent(PocReminderComponent, {
          horizontalPosition: 'center',
          verticalPosition: 'top',
          duration: 1000000000,
        });
      }
    });
  }

  ngAfterViewInit() {
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationEnd) {
        if (this.breakpointObserver.isMobileView()) {
          this.sidenav.close();
        }
      }
    });
  }

  toggleSidenav() {
    if (this.sidenav.opened) {
      this.sidenav.close();
    } else {
      this.sidenav.open();
    }
  }
}
