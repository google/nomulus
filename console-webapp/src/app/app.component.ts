// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import { AfterViewInit, Component, ViewChild, effect } from '@angular/core';
import { RegistrarService } from './registrar/registrar.service';
import { UserDataService } from './shared/services/userData.service';
import { GlobalLoaderService } from './shared/services/globalLoader.service';
import { MatSidenav } from '@angular/material/sidenav';
import { BreakPointObserverService } from './shared/services/breakPoint.service';
import { NavigationEnd, Router } from '@angular/router';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss'],
})
export class AppComponent implements AfterViewInit {
  renderRouter: boolean = true;

  @ViewChild(MatSidenav)
  sidenav!: MatSidenav;

  constructor(
    protected registrarService: RegistrarService,
    protected userDataService: UserDataService,
    protected globalLoader: GlobalLoaderService,
    protected breakpointObserver: BreakPointObserverService,
    private router: Router
  ) {
    effect(() => {
      if (registrarService.registrarId()) {
        this.renderRouter = false;
        setTimeout(() => {
          this.renderRouter = true;
        }, 400);
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
