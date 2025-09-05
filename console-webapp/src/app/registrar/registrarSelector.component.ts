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

import { Component, computed, effect, signal } from '@angular/core';
import { RegistrarService } from './registrar.service';

@Component({
  selector: 'app-registrar-selector',
  templateUrl: './registrarSelector.component.html',
  styleUrls: ['./registrarSelector.component.scss'],
  standalone: false,
})
export class RegistrarSelectorComponent {
  registrarInput = signal<string>(this.registrarService.registrarId());
  filteredOptions?: string[];
  allRegistrarIds = computed(() =>
    this.registrarService
      .registrars()
      .map((r) => r.registrarId)
      .sort()
  );

  constructor(protected registrarService: RegistrarService) {
    effect(() => {
      const filterValue = this.registrarInput().toLowerCase();
      this.filteredOptions = this.allRegistrarIds().filter((option) =>
        option.toLowerCase().includes(filterValue)
      );
    });
  }

  onFocus() {
    // We reset the list of options after selection, so that user doesn't have to clear it out
    this.filteredOptions = this.allRegistrarIds();
  }

  onSelect(registrarId: string) {
    this.registrarService.updateSelectedRegistrar(registrarId);
  }
}
