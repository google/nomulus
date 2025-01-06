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
import { RegistrarService } from 'src/app/registrar/registrar.service';

@Component({
  selector: 'app-selected-registrar-wrapper',
  styleUrls: ['./selectedRegistrarWrapper.component.scss'],
  template: `
    @if(registrarService.registrarId()){
    <ng-content></ng-content>
    } @else {
    <div class="console-app__emty-registrar">
      <h1>
        <mat-icon class="console-app__emty-registrar-icon">block</mat-icon>
      </h1>
      <h1>No registrar selected</h1>
      <h4 class="mat-body-2">Please select a registrar</h4>
    </div>
    }
  `,
})
export class SelectedRegistrarWrapper {
  constructor(protected registrarService: RegistrarService) {}
}
