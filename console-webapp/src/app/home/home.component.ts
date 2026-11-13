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

import { Component, ChangeDetectionStrategy } from '@angular/core';
import { Router } from '@angular/router';
import { PATHS } from '../app.routes';
import { MaterialModule } from '../material.module';
import {
  RESTRICTED_ELEMENTS,
  UserLevelVisibility,
} from '../shared/directives/userLevelVisiblity.directive';
import { BreakPointObserverService } from '../shared/services/breakPoint.service';

@Component({
  selector: 'app-home',
  imports: [MaterialModule, UserLevelVisibility],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
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
    this.router.navigate([PATHS.RegistrarComponent], {
      queryParamsHandling: 'merge',
    });
  }
  updateEppPassword() {
    this.router.navigate(
      [PATHS.SettingsComponent + '/' + PATHS.SecurityComponent],
      { queryParamsHandling: 'merge' }
    );
  }
  viewDums() {
    this.router.navigate([PATHS.DomainListComponent], {
      queryParamsHandling: 'merge',
    });
  }
}
