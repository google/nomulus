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
import { Router } from '@angular/router';
import { DomainListComponent } from '../domains/domainList.component';
import { RegistrarComponent } from '../registrar/registrarsTable.component';
import SecurityComponent from '../settings/security/security.component';
import { SettingsComponent } from '../settings/settings.component';
import { RESTRICTED_ELEMENTS } from '../shared/directives/userLevelVisiblity.directive';
import { BreakPointObserverService } from '../shared/services/breakPoint.service';

@Component({
  selector: 'app-home',
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss'],
})
export class HomeComponent {
  constructor(
    protected breakPointObserverService: BreakPointObserverService,
    private router: Router
  ) {}
  getElementIdForRegistrarsBlock() {
    return RESTRICTED_ELEMENTS.REGISTRAR_ELEMENT;
  }
  viewRegistrars() {
    this.router.navigate([RegistrarComponent.PATH], {
      queryParamsHandling: 'merge',
    });
  }
  updateEppPassword() {
    this.router.navigate(
      [SettingsComponent.PATH + '/' + SecurityComponent.PATH],
      { queryParamsHandling: 'merge' }
    );
  }
  viewDums() {
    this.router.navigate([DomainListComponent.PATH], {
      queryParamsHandling: 'merge',
    });
  }
}
